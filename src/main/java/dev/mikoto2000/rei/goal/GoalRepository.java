package dev.mikoto2000.rei.goal;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Durable goal identity, claims and pre-call reservations. Resume never replenishes a budget. */
@Repository
public class GoalRepository {
  public record FileCriterion(String relativeFile,String sha256) {}
  public record Goal(String id,String projectId,String projectRoot,String sessionId,String objective,
      String relativeFile,String sha256,int maxRuns,int maxLlmCalls,int attempts,int llmCallsUsed,
      String status,String currentRunId,String reason,List<FileCriterion> criteria) {
    public Goal { criteria=List.copyOf(criteria); }
    public Goal(String id,String projectId,String projectRoot,String sessionId,String objective,String relativeFile,String sha256,int maxRuns,int maxLlmCalls,int attempts,int llmCallsUsed,String status,String currentRunId,String reason) {
      this(id,projectId,projectRoot,sessionId,objective,relativeFile,sha256,maxRuns,maxLlmCalls,attempts,llmCallsUsed,status,currentRunId,reason,List.of(new FileCriterion(relativeFile,sha256)));
    }
  }
  public record Claim(Goal goal,String token) {}
  public record Attempt(String runId,int number,String status,String reason) {}
  private final JdbcClient db;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public GoalRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS agent_goals(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,objective TEXT NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,max_runs INTEGER NOT NULL,max_calls INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,run TEXT,token TEXT,reason TEXT NOT NULL DEFAULT '')").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_goal_criteria(goal TEXT NOT NULL,ordinal INTEGER NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(goal,ordinal))").update();
    db.sql("CREATE UNIQUE INDEX IF NOT EXISTS agent_goals_running_session ON agent_goals(project,session) WHERE status='RUNNING'").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_goal_attempts(goal TEXT NOT NULL,run TEXT PRIMARY KEY,number INTEGER NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',UNIQUE(goal,number))").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_goal_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,goal TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,timestamp INTEGER NOT NULL)").update();
  }
  private final RowMapper<Goal> ROW=(rs,n)->new Goal(rs.getString("id"),rs.getString("project"),rs.getString("root"),rs.getString("session"),
      rs.getString("objective"),rs.getString("file"),rs.getString("digest"),rs.getInt("max_runs"),rs.getInt("max_calls"),rs.getInt("attempts"),rs.getInt("used"),
      rs.getString("status"),rs.getString("run"),rs.getString("reason"),criteria(rs.getString("id"),rs.getString("file"),rs.getString("digest")));

  public Goal create(AgentRunContext owner,String objective,String relativeFile,String sha256,int maxRuns,int maxLlmCalls) {
    if(owner==null||owner.projectId()==null||owner.projectId().isBlank()||owner.conversationId().isBlank())throw new IllegalArgumentException("Goal requires an owning Project and Session");
    if(objective==null||objective.isBlank()||objective.length()>4096)throw new IllegalArgumentException("Objective must contain 1..4096 characters");
    validateFile(relativeFile);
    if(sha256==null||!sha256.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Expected SHA-256 must contain 64 hexadecimal characters");
    if(maxRuns<1||maxRuns>10||maxLlmCalls<1||maxLlmCalls>100)throw new IllegalArgumentException("Goal budgets: 1..10 Runs and 1..100 LLM calls");
    String id="goal-"+UUID.randomUUID();
    transaction.executeWithoutResult(status->{
      if(db.sql("INSERT INTO agent_goals(id,project,root,session,objective,file,digest,max_runs,max_calls,status) SELECT ?,?,?,?,?,?,?,?,?,'READY' WHERE (SELECT COUNT(*) FROM agent_goals WHERE project=? AND status NOT IN ('COMPLETED','CANCELLED'))<256")
          .params(id,owner.projectId(),owner.projectRoot().toString(),owner.conversationId(),objective,relativeFile,sha256.toLowerCase(Locale.ROOT),maxRuns,maxLlmCalls,owner.projectId()).update()!=1)
        throw new IllegalStateException("Project goal limit reached (256)");
      history(id,"READY","created");
    });return get(owner.projectId(),id);
  }
  private List<FileCriterion> criteria(String id,String file,String digest) {
    var items=db.sql("SELECT file,digest FROM agent_goal_criteria WHERE goal=? ORDER BY ordinal").param(id)
        .query((rs,n)->new FileCriterion(rs.getString("file"),rs.getString("digest"))).list();
    return items.isEmpty()?List.of(new FileCriterion(file,digest)):items;
  }
  public Goal create(AgentRunContext owner,String objective,List<FileCriterion> criteria,int maxRuns,int maxLlmCalls) {
    if(criteria==null||criteria.isEmpty()||criteria.size()>16)throw new IllegalArgumentException("Goal requires 1..16 file criteria");
    var normalized=new ArrayList<FileCriterion>();var paths=new HashSet<Path>();
    for(var item:criteria) {
      if(item==null)throw new IllegalArgumentException("File criterion is required");
      validateFile(item.relativeFile());var path=Path.of(item.relativeFile()).normalize();
      if(!paths.add(path))throw new IllegalArgumentException("Duplicate completion file");
      if(item.sha256()==null||!item.sha256().matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Expected SHA-256 must contain 64 hexadecimal characters");
      normalized.add(new FileCriterion(path.toString().replace('\\','/'),item.sha256().toLowerCase(Locale.ROOT)));
    }
    return transaction.execute(status->{
      var first=normalized.getFirst();var goal=create(owner,objective,first.relativeFile(),first.sha256(),maxRuns,maxLlmCalls);
      for(int i=0;i<normalized.size();i++) {
        var item=normalized.get(i);
        db.sql("INSERT INTO agent_goal_criteria(goal,ordinal,file,digest) VALUES(?,?,?,?)").params(goal.id(),i,item.relativeFile(),item.sha256()).update();
      }
      return get(owner.projectId(),goal.id());
    });
  }
  static void validateFile(String file) {
    if(file==null||file.isBlank()||file.length()>1024)throw new IllegalArgumentException("Relative file is required (up to 1024 characters)");
    var path=Path.of(file);
    if(path.isAbsolute()||path.getRoot()!=null||file.contains(":")||path.normalize().toString().isEmpty())throw new IllegalArgumentException("File must be a Project-relative path");
    for(var part:path)if(part.toString().equals(".."))throw new IllegalArgumentException("Parent traversal is not permitted");
  }
  public Goal get(String project,String id) {
    return db.sql("SELECT * FROM agent_goals WHERE project=? AND id=?").params(project,id).query(ROW).optional()
        .orElseThrow(()->new IllegalArgumentException("Goal not found in this Project"));
  }
  public List<Goal> list(String project) {return db.sql("SELECT * FROM agent_goals WHERE project=? ORDER BY id LIMIT 256").param(project).query(ROW).list();}
  public Claim claim(String project,String id) {
    return transaction.execute(status->{
      get(project,id);String token=UUID.randomUUID().toString();
      var goal=db.sql("UPDATE agent_goals SET status='RUNNING',token=?,reason='' WHERE project=? AND id=? AND status IN ('READY','PAUSED','WAITING_APPROVAL','FAILED','BLOCKED') AND attempts<max_runs AND used<max_calls RETURNING *")
          .params(token,project,id).query(ROW).optional().orElseThrow(()->new IllegalStateException("Goal is running, terminal, or its budget is exhausted"));
      history(id,"RUNNING","explicit_run");return new Claim(goal,token);
    });
  }
  public String beginAttempt(Claim claim) {
    return transaction.execute(status->{
      String run=UUID.randomUUID().toString();
      int count=db.sql("UPDATE agent_goals SET attempts=attempts+1,run=? WHERE id=? AND project=? AND token=? AND status='RUNNING' AND attempts<max_runs AND used<max_calls")
          .params(run,claim.goal().id(),claim.goal().projectId(),claim.token()).update();
      if(count!=1)throw new IllegalStateException("Goal claim inactive or budget exhausted");
      var goal=get(claim.goal().projectId(),claim.goal().id());
      db.sql("INSERT INTO agent_goal_attempts(goal,run,number,status) VALUES(?,?,?,'RUNNING')").params(goal.id(),run,goal.attempts()).update();return run;
    });
  }
  /** Charged before an LLM invocation; failed or uncertain calls never restore the reservation. */
  public boolean reserveLlm(Claim claim) {
    return db.sql("UPDATE agent_goals SET used=used+1 WHERE id=? AND project=? AND token=? AND status='RUNNING' AND used<max_calls")
        .params(claim.goal().id(),claim.goal().projectId(),claim.token()).update()==1;
  }
  public int remainingLlm(Claim claim) {
    return db.sql("SELECT max_calls-used FROM agent_goals WHERE id=? AND project=? AND token=? AND status='RUNNING'")
        .params(claim.goal().id(),claim.goal().projectId(),claim.token()).query(Integer.class).optional().orElse(0);
  }
  public boolean active(Claim claim) {return db.sql("SELECT COUNT(*) FROM agent_goals WHERE id=? AND project=? AND token=? AND status='RUNNING'")
      .params(claim.goal().id(),claim.goal().projectId(),claim.token()).query(Integer.class).single()==1;}
  public void recordAttempt(Claim claim,String run,String state,String reason) {
    if(!Set.of("VERIFIED","UNVERIFIED","FAILED","CANCELLED","WAITING_APPROVAL","BLOCKED").contains(state))throw new IllegalArgumentException("Invalid attempt state");
    transaction.executeWithoutResult(status->{
      int updated=db.sql("UPDATE agent_goal_attempts SET status=?,reason=? WHERE goal=? AND run=? AND status='RUNNING' AND EXISTS(SELECT 1 FROM agent_goals WHERE id=? AND project=? AND token=? AND run=?)")
          .params(state,reason,claim.goal().id(),run,claim.goal().id(),claim.goal().projectId(),claim.token(),run).update();
      if(updated!=1)throw new IllegalStateException("Attempt is no longer owned");
      history(claim.goal().id(),state,reason);
    });
  }
  public Goal stop(Claim claim,String state,String reason) {
    if(!Set.of("COMPLETED","FAILED","PAUSED","BLOCKED","WAITING_APPROVAL").contains(state))throw new IllegalArgumentException("Invalid Goal state");
    transaction.executeWithoutResult(status->{
      if(db.sql("UPDATE agent_goals SET status=?,reason=?,token=NULL WHERE id=? AND project=? AND token=? AND status='RUNNING'")
          .params(state,reason,claim.goal().id(),claim.goal().projectId(),claim.token()).update()!=1)throw new IllegalStateException("Goal claim inactive");
      history(claim.goal().id(),state,reason);
    });return get(claim.goal().projectId(),claim.goal().id());
  }
  public Goal verifiedWithoutRun(String project,String id) {
    transaction.executeWithoutResult(status->{
      get(project,id);
      if(db.sql("UPDATE agent_goals SET status='COMPLETED',reason='file_digest_verified' WHERE project=? AND id=? AND status NOT IN ('RUNNING','CANCELLED','COMPLETED')")
          .params(project,id).update()!=1)throw new IllegalStateException("Goal is running or terminal");
      history(id,"COMPLETED","file_digest_verified");
    });return get(project,id);
  }
  /** Human reconciliation only: unknown side effects remain unknown; no budget or evidence is restored. */
  public Goal reconcile(String project,String id,String expectedRunId) {
    transaction.executeWithoutResult(status->{
      get(project,id);
      if(db.sql("UPDATE agent_goals SET status='PAUSED',reason='uncertain_run_reconciled',token=NULL WHERE project=? AND id=? AND status='RUNNING' AND run IS ?")
          .params(project,id,expectedRunId).update()!=1)throw new IllegalStateException("Goal is no longer running with the specified Run ID");
      db.sql("UPDATE agent_goal_attempts SET status='BLOCKED',reason='uncertain_run_reconciled' WHERE goal=? AND status='RUNNING'").param(id).update();
      history(id,"PAUSED","uncertain_run_reconciled");
    });return get(project,id);
  }
  public Goal cancel(String project,String id) {
    transaction.executeWithoutResult(status->{
      get(project,id);
      if(db.sql("UPDATE agent_goals SET status='CANCELLED',reason='human_cancelled',token=NULL WHERE project=? AND id=? AND status NOT IN ('COMPLETED','CANCELLED')")
          .params(project,id).update()!=1)throw new IllegalStateException("Goal is already terminal");
      db.sql("UPDATE agent_goal_attempts SET status='CANCELLED',reason='human_cancelled' WHERE goal=? AND status='RUNNING'").param(id).update();
      history(id,"CANCELLED","human_cancelled");
    });return get(project,id);
  }
  public List<Attempt> attempts(String project,String id) {
    get(project,id);return db.sql("SELECT * FROM agent_goal_attempts WHERE goal=? ORDER BY number").param(id)
        .query((rs,n)->new Attempt(rs.getString("run"),rs.getInt("number"),rs.getString("status"),rs.getString("reason"))).list();
  }
  public record History(String status,String reason,Instant timestamp) {}
  public List<History> history(String project,String id) {
    get(project,id);return db.sql("SELECT * FROM agent_goal_history WHERE goal=? ORDER BY sequence").param(id)
        .query((rs,n)->new History(rs.getString("status"),rs.getString("reason"),Instant.ofEpochMilli(rs.getLong("timestamp")))).list();
  }
  private void history(String id,String status,String reason) {db.sql("INSERT INTO agent_goal_history(goal,status,reason,timestamp) VALUES(?,?,?,?)").params(id,status,reason,clock.millis()).update();}
}
