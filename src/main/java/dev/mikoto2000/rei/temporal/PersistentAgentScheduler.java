package dev.mikoto2000.rei.temporal;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** One-shot continuations. A claimed action is never automatically retried after uncertain execution. */
@Component
public class PersistentAgentScheduler implements AgentScheduler {
  public record Entry(ScheduledAgentTask task,String projectId,String projectRoot,String status,String runId,String outcome) {}
  public record History(String status,Instant timestamp,String detail) {}
  private final JdbcClient db;
  private final Clock clock;
  private final TransactionTemplate transaction;
  public PersistentAgentScheduler(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);this.clock=clock;transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedules(id TEXT PRIMARY KEY,created INTEGER NOT NULL,due INTEGER NOT NULL,action TEXT NOT NULL,session TEXT NOT NULL,project TEXT NOT NULL,root TEXT NOT NULL,status TEXT NOT NULL,run TEXT,outcome TEXT NOT NULL DEFAULT '')").update();
    db.sql("CREATE INDEX IF NOT EXISTS agent_schedules_due ON agent_schedules(status,due)").update();
    db.sql("CREATE UNIQUE INDEX IF NOT EXISTS agent_schedules_session_running ON agent_schedules(project,session) WHERE status='RUNNING'").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedule_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL,status TEXT NOT NULL,timestamp INTEGER NOT NULL,detail TEXT NOT NULL)").update();
  }
  private static final RowMapper<Entry> ROW=(rs,n)->new Entry(new ScheduledAgentTask(rs.getString("id"),Instant.ofEpochMilli(rs.getLong("created")),
      Instant.ofEpochMilli(rs.getLong("due")),rs.getString("action"),rs.getString("session")),rs.getString("project"),rs.getString("root"),rs.getString("status"),rs.getString("run"),rs.getString("outcome"));
  @Override public ScheduledAgentTask scheduleAfter(Duration duration,String action,String conversationId) {
    if(duration==null||duration.isNegative()||duration.compareTo(Duration.ofDays(366))>0)throw new IllegalArgumentException("Duration must be 0..366 days");
    return scheduleAt(clock.instant().plus(duration),action,conversationId);
  }
  @Override public ScheduledAgentTask scheduleAt(Instant executeAt,String action,String conversationId) {
    var owner=AgentRunScope.current();
    if(owner==null||owner.projectId()==null||owner.projectId().isBlank()||!owner.conversationId().equals(conversationId))
      throw new IllegalArgumentException("Schedule requires the current owning project and exact conversationId");
    if(action==null||action.isBlank()||action.length()>4096)throw new IllegalArgumentException("Action must contain 1..4096 characters");
    if(executeAt==null||executeAt.isBefore(clock.instant())||executeAt.isAfter(clock.instant().plus(Duration.ofDays(366))))
      throw new IllegalArgumentException("Execute time must be now..366 days in the future");
    var task=new ScheduledAgentTask("timer-"+UUID.randomUUID(),clock.instant(),executeAt,action,conversationId);
    transaction.executeWithoutResult(status->{
      int count=db.sql("INSERT INTO agent_schedules(id,created,due,action,session,project,root,status) SELECT ?,?,?,?,?,?,?,'PENDING' WHERE (SELECT COUNT(*) FROM agent_schedules WHERE project=? AND status IN ('PENDING','SCHEDULED','RUNNING'))<256")
          .params(task.id(),task.createdAt().toEpochMilli(),executeAt.toEpochMilli(),action,conversationId,owner.projectId(),owner.projectRoot().toString(),owner.projectId()).update();
      if(count!=1)throw new IllegalStateException("Project schedule limit reached (256)");
      history(task.id(),"PENDING","");
    });return task;
  }
  @Override public List<ScheduledAgentTask> list() {
    var owner=AgentRunScope.current();if(owner==null||owner.projectId()==null)throw new IllegalArgumentException("Owning project is required");
    return db.sql("SELECT * FROM agent_schedules WHERE project=? AND session=? AND status IN ('PENDING','SCHEDULED','RUNNING') ORDER BY due,id LIMIT 256")
        .params(owner.projectId(),owner.conversationId()).query(ROW).list().stream().map(Entry::task).toList();
  }
  public List<Entry> list(String project) {return db.sql("SELECT * FROM agent_schedules WHERE project=? ORDER BY created DESC,id LIMIT 256").param(project).query(ROW).list();}
  public Entry get(String project,String id) {return db.sql("SELECT * FROM agent_schedules WHERE project=? AND id=?").params(project,id).query(ROW).optional()
      .orElseThrow(()->new IllegalArgumentException("Schedule not found in this project"));}
  /** Human-facing control only; no Tool exposes activation. */
  public void activate(String project,String id) {transition(project,id,"PENDING","SCHEDULED");}
  public void cancel(String project,String id) {
    transaction.executeWithoutResult(status->{get(project,id);
      if(db.sql("UPDATE agent_schedules SET status='CANCELLED' WHERE project=? AND id=? AND status IN ('PENDING','SCHEDULED')").params(project,id).update()!=1)
        throw new IllegalStateException("Only unclaimed schedules can be cancelled; stop a running Run separately");
      history(id,"CANCELLED","");
    });
  }
  private void transition(String project,String id,String from,String to) {
    transaction.executeWithoutResult(status->{get(project,id);
      if(db.sql("UPDATE agent_schedules SET status=? WHERE project=? AND id=? AND status=?").params(to,project,id,from).update()!=1)
        throw new IllegalStateException("Schedule already activated or terminal");
      history(id,to,"");
    });
  }
  public Optional<Entry> claimDue() {
    return transaction.execute(status->{
      var result=db.sql("UPDATE agent_schedules SET status='RUNNING',run=? WHERE id=(SELECT candidate.id FROM agent_schedules candidate WHERE candidate.status='SCHEDULED' AND candidate.due<=? AND NOT EXISTS(SELECT 1 FROM agent_schedules active WHERE active.project=candidate.project AND active.session=candidate.session AND active.status='RUNNING') ORDER BY candidate.due,candidate.id LIMIT 1) AND status='SCHEDULED' RETURNING *")
          .params(UUID.randomUUID().toString(),clock.millis()).query(ROW).optional();
      result.ifPresent(entry->history(entry.task().id(),"RUNNING",entry.runId()));return result;
    });
  }
  public void finish(Entry claim,String state,String detail) {
    if(!Set.of("COMPLETED","FAILED","CANCELLED").contains(state))throw new IllegalArgumentException("Invalid terminal status");
    transaction.executeWithoutResult(status->{
      String outcome=preview(detail);
      if(db.sql("UPDATE agent_schedules SET status=?,outcome=? WHERE id=? AND project=? AND run=? AND status='RUNNING'")
          .params(state,outcome,claim.task().id(),claim.projectId(),claim.runId()).update()!=1)throw new IllegalStateException("Claim no longer active");
      history(claim.task().id(),state,outcome);
    });
  }
  public List<History> history(String project,String id) {
    get(project,id);return db.sql("SELECT * FROM agent_schedule_history WHERE id=? ORDER BY sequence").param(id)
        .query((rs,n)->new History(rs.getString("status"),Instant.ofEpochMilli(rs.getLong("timestamp")),rs.getString("detail"))).list();
  }
  private void history(String id,String status,String detail) {db.sql("INSERT INTO agent_schedule_history(id,status,timestamp,detail) VALUES(?,?,?,?)").params(id,status,clock.millis(),preview(detail)).update();}
  static String preview(String text) {String safe=CredentialRedactor.redact(text==null?"":text);return safe.substring(0,Math.min(512,safe.length()));}
}
