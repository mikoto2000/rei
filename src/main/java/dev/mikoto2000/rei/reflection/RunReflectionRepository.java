package dev.mikoto2000.rei.reflection;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.event.*;

/** Durable facts and review candidates; candidates are not automatically promoted to trusted memories. */
@Repository
public class RunReflectionRepository {
  public record Action(String callId,String tool,String status,String errorType) {}
  public record Item(String id,String projectId,String sessionId,String runId,String sourceKind,String sourceId,
      String status,String expected,List<Action> actions,boolean actionsTruncated,String failureReason,String gap,
      String nextAction,List<String> knowledgeCandidates,String sourceEvent,Instant createdAt) {
    public Item {actions=List.copyOf(actions);knowledgeCandidates=List.copyOf(knowledgeCandidates);}
  }
  private final JdbcClient db;private final Clock clock;private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
  public RunReflectionRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS run_reflection_actions(project TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,call_id TEXT NOT NULL,tool TEXT NOT NULL,status TEXT NOT NULL,error_type TEXT NOT NULL,PRIMARY KEY(project,session,run,call_id,status))").update();
    db.sql("CREATE TABLE IF NOT EXISTS run_reflection_tasks(project TEXT NOT NULL,session TEXT NOT NULL,task TEXT NOT NULL,title TEXT NOT NULL,PRIMARY KEY(project,session,task))").update();
    db.sql("CREATE TABLE IF NOT EXISTS run_reflections(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,document TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(project,session,kind,source,status))").update();
    db.sql("CREATE INDEX IF NOT EXISTS run_reflections_project ON run_reflections(project,created)").update();
  }
  void action(AgentEvent event,Action action) {
    if(event.runId()==null||!valid(action.callId())||!valid(action.tool()))return;
    // Keep one extra row to prove truncation. Further calls do not grow a Run without bound.
    db.sql("INSERT OR IGNORE INTO run_reflection_actions SELECT ?,?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM run_reflection_actions WHERE project=? AND session=? AND run=?)<257")
        .params(event.projectId(),event.sessionId(),event.runId(),action.callId(),safe(action.tool(),128),action.status(),safe(action.errorType(),128),event.projectId(),event.sessionId(),event.runId()).update();
  }
  void task(AgentEvent event,TaskCreatedPayload task) {
    if(!valid(task.taskId()))return;
    db.sql("INSERT OR IGNORE INTO run_reflection_tasks VALUES(?,?,?,?)").params(event.projectId(),event.sessionId(),task.taskId(),safe(task.title(),512)).update();
  }
  Item reflect(AgentEvent event,String kind,String source,String status,String failure) {
    if(!valid(source))throw new IllegalArgumentException("Reflection source identity required");
    List<Action> observed=!kind.equals("RUN")||event.runId()==null?List.of():db.sql("SELECT * FROM run_reflection_actions WHERE project=? AND session=? AND run=? ORDER BY rowid LIMIT 257")
        .params(event.projectId(),event.sessionId(),event.runId()).query((rs,n)->new Action(rs.getString("call_id"),rs.getString("tool"),rs.getString("status"),rs.getString("error_type"))).list();
    boolean truncated=observed.size()>256;List<Action> actions=observed.stream().limit(256).toList();
    String expected=kind.equals("TASK")?db.sql("SELECT title FROM run_reflection_tasks WHERE project=? AND session=? AND task=?")
        .params(event.projectId(),event.sessionId(),source).query(String.class).optional().orElse("TASK_COMPLETION_REQUESTED"):"TASK_CRITERIA_NOT_DECLARED";
    boolean permission=actions.stream().anyMatch(a->Set.of("PermissionRequired","PermissionDenied").contains(a.errorType()));
    boolean toolFailed=actions.stream().anyMatch(a->a.status().equals("FAILED"));
    String gap=status.equals("COMPLETED")?(kind.equals("TASK")?"TASK_COMPLETION_REPORTED":"TASK_CRITERIA_NOT_DECLARED"):status.equals("CANCELLED")?"EXECUTION_CANCELLED":"EXECUTION_FAILED";
    String next=permission?"REVIEW_APPROVAL_BEFORE_RETRY":!status.equals("COMPLETED")?"INSPECT_FAILURE_AND_RECONCILE_BEFORE_RETRY":toolFailed?"REVIEW_FAILED_TOOLS_AND_VERIFY_CRITERIA":"INSPECT_RESULT_AND_VERIFY_CRITERIA";
    var knowledge=new ArrayList<String>();
    if(permission)knowledge.add("Review the required capability and explicit approval before retrying this task; an inbox acknowledgement is not permission.");
    if(toolFailed)knowledge.add("A Tool failed in this execution. Preserve its call identity and inspect the recorded failure before choosing a retry.");
    actions.stream().filter(a->a.status().equals("FAILED")).limit(8).forEach(a->knowledge.add("Recorded failed Tool "+safe(a.tool(),128)+" (call "+safe(a.callId(),128)+", error type "+safe(a.errorType(),128)+"). Inspect this specific operation before selecting a future retry."));
    if(!status.equals("COMPLETED"))knowledge.add("Recorded "+status+" for "+kind+" "+safe(source,128)+" with error type "+safe(failure,128)+". Reconcile this source before reusing its execution result.");
    if(status.equals("COMPLETED"))knowledge.add("Normal execution termination or a reported Task completion does not replace independent verification of the requested result.");
    if(truncated)knowledge.add("The action record exceeded 256 facts; review the full Project event history before drawing a complete conclusion.");
    var item=new Item("run-reflection-"+UUID.randomUUID(),event.projectId(),event.sessionId(),event.runId(),kind,source,status,expected,actions,truncated,safe(failure,128),gap,next,knowledge,event.id(),clock.instant());
    db.sql("INSERT OR IGNORE INTO run_reflections VALUES(?,?,?,?,?,?,?,?)").params(item.id(),item.projectId(),item.sessionId(),kind,source,status,json(item),clock.millis()).update();
    return read(db.sql("SELECT document FROM run_reflections WHERE project=? AND session=? AND kind=? AND source=? AND status=?")
        .params(item.projectId(),item.sessionId(),kind,source,status).query(String.class).single());
  }
  public List<Item> list(String project){return db.sql("SELECT document FROM run_reflections WHERE project=? ORDER BY created DESC,id LIMIT 256").param(project).query(String.class).list().stream().map(this::read).toList();}
  public Item get(String project,String id){return db.sql("SELECT document FROM run_reflections WHERE project=? AND id=?").params(project,id).query(String.class).optional().map(this::read).orElseThrow(()->new IllegalArgumentException("Run reflection not found in this Project"));}
  private String json(Item item){try{return mapper.writeValueAsString(item);}catch(java.io.IOException error){throw new IllegalStateException("Cannot encode reflection",error);}}
  private Item read(String json){try{return mapper.readValue(json,Item.class);}catch(java.io.IOException error){throw new IllegalStateException("Cannot read reflection",error);}}
  static boolean valid(String value){return value!=null&&!value.isBlank()&&value.length()<=512;}
  static String safe(String value,int length){String safe=CredentialRedactor.redact(value==null?"":value).replaceAll("[\\p{Cntrl}]"," ");return safe.substring(0,Math.min(length,safe.length()));}
}
