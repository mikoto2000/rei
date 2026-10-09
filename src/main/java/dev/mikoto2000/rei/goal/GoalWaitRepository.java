package dev.mikoto2000.rei.goal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
/** Durable binding between existing Goal, Dependency, Scheduler and Checkpoint identities. */
@org.springframework.stereotype.Repository
public class GoalWaitRepository {
 public record Snapshot(GoalCompletionProgress progress,GoalCompletionGate.Definition completion,DependencySpec condition,
     String checkpointTask,long checkpointRevision,List<PersistentCheckpoint.Operation> operations,String resumeInfo){
  public Snapshot{operations=List.copyOf(operations);}
 }
 public record Wait(String id,String project,String goalId,String runId,int attempts,String reason,String dependencyId,String scheduleId,
     String state,String lastObservation,long version,String resumedRunId,Snapshot snapshot){}
 private final JdbcClient db;private final TransactionTemplate tx;private final Clock clock;
 private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
 public GoalWaitRepository(@Qualifier("memoryConsolidationDataSource")DataSource source,Clock clock){
  db=JdbcClient.create(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.clock=clock;
  db.sql("CREATE TABLE IF NOT EXISTS agent_goal_waits(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,project TEXT NOT NULL,goal TEXT NOT NULL,run TEXT,attempts INTEGER NOT NULL,reason TEXT NOT NULL,dependency TEXT NOT NULL,schedule TEXT NOT NULL UNIQUE,state TEXT NOT NULL,observed TEXT NOT NULL,version INTEGER NOT NULL,resumed_run TEXT,snapshot TEXT NOT NULL,created INTEGER NOT NULL)").update();
  db.sql("CREATE UNIQUE INDEX IF NOT EXISTS agent_goal_wait_active ON agent_goal_waits(project,goal) WHERE state IN ('WAITING','BLOCKED','RESUMING')").update();
 }
 private static String encode(Snapshot snapshot){try{String result=JSON.writeValueAsString(snapshot);if(result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("Wait snapshot exceeds 128KiB");return result;}catch(java.io.IOException e){throw new IllegalStateException("Wait snapshot unavailable",e);}}
 private static String encodeDefinition(GoalCompletionGate.Definition definition){try{return JSON.writeValueAsString(definition);}catch(java.io.IOException e){throw new IllegalArgumentException(e);}}
 private static Snapshot decode(String json){try{return JSON.readValue(json,Snapshot.class);}catch(java.io.IOException e){throw new IllegalStateException("Wait snapshot unavailable",e);}}
 private final org.springframework.jdbc.core.RowMapper<Wait> row=(rs,n)->new Wait(rs.getString("id"),rs.getString("project"),rs.getString("goal"),rs.getString("run"),rs.getInt("attempts"),rs.getString("reason"),rs.getString("dependency"),rs.getString("schedule"),rs.getString("state"),rs.getString("observed"),rs.getLong("version"),rs.getString("resumed_run"),decode(rs.getString("snapshot")));
 public Wait create(GoalRepository.Goal goal,String dependency,String reason,Snapshot snapshot,Supplier<String> schedule){
  String encoded=encode(snapshot);
  return tx.execute(status->{
   if(active(goal.projectId(),goal.id()))throw new IllegalStateException("Goal already has an active wait");
   if(db.sql("SELECT COUNT(*) FROM agent_goal_waits WHERE project=? AND state IN ('WAITING','BLOCKED','RESUMING')").param(goal.projectId()).query(Integer.class).single()>=256)throw new IllegalStateException("Goal wait capacity reached");
   if(db.sql("UPDATE agent_goals SET status='PAUSED',reason='dependency_wait' WHERE project=? AND id=? AND run IS ? AND attempts=? AND status=? AND status IN ('READY','PAUSED','FAILED','WAITING_APPROVAL','BLOCKED') AND completion_json IS ?")
      .params(goal.projectId(),goal.id(),goal.currentRunId(),goal.attempts(),goal.status(),goal.completion()==null?null:encodeDefinition(goal.completion())).update()!=1)throw new IllegalStateException("Goal changed before wait");
   db.sql("INSERT INTO agent_goal_history(goal,status,reason,timestamp) VALUES(?,'PAUSED','dependency_wait',?)").params(goal.id(),clock.millis()).update();
   String id=UUID.randomUUID().toString(),timer=schedule.get();
   db.sql("INSERT INTO agent_goal_waits(id,project,goal,run,attempts,reason,dependency,schedule,state,observed,version,snapshot,created) VALUES(?,?,?,?,?,?,?,?,'WAITING','not_observed',1,?,?)")
      .params(id,goal.projectId(),goal.id(),goal.currentRunId(),goal.attempts(),reason,dependency,timer,encoded,clock.millis()).update();
   return byId(goal.projectId(),id);
  });
 }
 private Wait byId(String project,String id){return db.sql("SELECT * FROM agent_goal_waits WHERE project=? AND id=?").params(project,id).query(row).single();}
 public Wait get(String project,String goal){return db.sql("SELECT * FROM agent_goal_waits WHERE project=? AND goal=? ORDER BY sequence DESC LIMIT 1").params(project,goal).query(row).optional().orElseThrow(()->new IllegalArgumentException("Goal wait not found"));}
 public Optional<Wait> bySchedule(String project,String id){return db.sql("SELECT * FROM agent_goal_waits WHERE project=? AND schedule=?").params(project,id).query(row).optional();}
 public boolean active(String project,String goal){return db.sql("SELECT COUNT(*) FROM agent_goal_waits WHERE project=? AND goal=? AND state IN ('WAITING','BLOCKED','RESUMING')").params(project,goal).query(Integer.class).single()>0;}
 public Wait transition(Wait expected,String state,String observed,String newRun){
  if(!Set.of("WAITING","BLOCKED","RESUMING","RESUMED","CANCELLED").contains(state)||observed==null||!observed.matches("[a-z_]{1,80}"))throw new IllegalArgumentException("Structured wait transition required");
  if(db.sql("UPDATE agent_goal_waits SET state=?,observed=?,resumed_run=?,version=version+1 WHERE project=? AND id=? AND version=? AND state=?")
      .params(state,observed,newRun,expected.project(),expected.id(),expected.version(),expected.state()).update()!=1)throw new IllegalStateException("Goal wait changed");
  return byId(expected.project(),expected.id());
 }
}