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
  public record FileCriterion(String relativeFile,String sha256,String jsonPointer,String expectedJson) {
    public FileCriterion(String relativeFile,String sha256){this(relativeFile,sha256,null,null);}
    public boolean jsonCriterion(){return jsonPointer!=null||expectedJson!=null;}
  }
  public record Goal(String id,String projectId,String projectRoot,String sessionId,String objective,
      String relativeFile,String sha256,int maxRuns,int maxLlmCalls,int attempts,int llmCallsUsed,
      String status,String currentRunId,String reason,List<FileCriterion> criteria,
      long maxTotalTokens,long totalTokens,boolean tokenUsageUnknown,int pendingLlmCalls) {
    public Goal { criteria=List.copyOf(criteria); }
    public Goal(String id,String projectId,String projectRoot,String sessionId,String objective,String relativeFile,String sha256,int maxRuns,int maxLlmCalls,int attempts,int llmCallsUsed,String status,String currentRunId,String reason,List<FileCriterion> criteria) {
      this(id,projectId,projectRoot,sessionId,objective,relativeFile,sha256,maxRuns,maxLlmCalls,attempts,llmCallsUsed,status,currentRunId,reason,criteria,0,0,false,0);
    }
    public Goal(String id,String projectId,String projectRoot,String sessionId,String objective,String relativeFile,String sha256,int maxRuns,int maxLlmCalls,int attempts,int llmCallsUsed,String status,String currentRunId,String reason) {
      this(id,projectId,projectRoot,sessionId,objective,relativeFile,sha256,maxRuns,maxLlmCalls,attempts,llmCallsUsed,status,currentRunId,reason,List.of(new FileCriterion(relativeFile,sha256)));
    }
  }
  public record Claim(Goal goal,String token) {}
  private record CriterionKey(Path file,String predicate) {}
  public record Attempt(String runId,int number,String status,String reason) {}
  private final JdbcClient db;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final dev.mikoto2000.rei.llm.LlmProperties properties;

  public GoalRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    this(source,clock,new dev.mikoto2000.rei.llm.LlmProperties());
  }
  @org.springframework.beans.factory.annotation.Autowired
  public GoalRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock,dev.mikoto2000.rei.llm.LlmProperties properties) {
    this.properties=properties;
    db=JdbcClient.create(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS agent_goals(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,objective TEXT NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,max_runs INTEGER NOT NULL,max_calls INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,run TEXT,token TEXT,reason TEXT NOT NULL DEFAULT '')").update();
    var columns=new HashSet<>(db.sql("PRAGMA table_info(agent_goals)").query((rs,n)->rs.getString("name")).list());
    for(String column:List.of("max_tokens","tokens_used","tokens_unknown","tokens_pending")) {
      if(!columns.contains(column))db.sql("ALTER TABLE agent_goals ADD COLUMN "+column+" INTEGER NOT NULL DEFAULT 0").update();
    }
    db.sql("CREATE TABLE IF NOT EXISTS agent_goal_criteria(goal TEXT NOT NULL,ordinal INTEGER NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(goal,ordinal))").update();
    var criterionColumns=new HashSet<>(db.sql("PRAGMA table_info(agent_goal_criteria)").query((rs,n)->rs.getString("name")).list());
    for(String column:List.of("json_pointer","expected_json")) {
      if(!criterionColumns.contains(column))db.sql("ALTER TABLE agent_goal_criteria ADD COLUMN "+column+" TEXT").update();
    }
    db.sql("CREATE UNIQUE INDEX IF NOT EXISTS agent_goals_running_session ON agent_goals(project,session) WHERE status='RUNNING'").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_goal_attempts(goal TEXT NOT NULL,run TEXT PRIMARY KEY,number INTEGER NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',UNIQUE(goal,number))").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_goal_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,goal TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,timestamp INTEGER NOT NULL)").update();
  }
  private final RowMapper<Goal> ROW=(rs,n)->new Goal(rs.getString("id"),rs.getString("project"),rs.getString("root"),rs.getString("session"),
      rs.getString("objective"),rs.getString("file"),rs.getString("digest"),rs.getInt("max_runs"),rs.getInt("max_calls"),rs.getInt("attempts"),rs.getInt("used"),
      rs.getString("status"),rs.getString("run"),rs.getString("reason"),criteria(rs.getString("id"),rs.getString("file"),rs.getString("digest")),
      rs.getLong("max_tokens"),rs.getLong("tokens_used"),rs.getBoolean("tokens_unknown"),rs.getInt("tokens_pending"));

  public Goal create(AgentRunContext owner,String objective,String relativeFile,String sha256,int maxRuns,int maxLlmCalls) {
    if(sha256==null||!sha256.matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Expected SHA-256 must contain 64 hexadecimal characters");
    return createBase(owner,objective,relativeFile,sha256.toLowerCase(Locale.ROOT),maxRuns,maxLlmCalls);
  }
  private Goal createBase(AgentRunContext owner,String objective,String relativeFile,String sha256,int maxRuns,int maxLlmCalls) {
    if(owner==null||owner.projectId()==null||owner.projectId().isBlank()||owner.conversationId().isBlank())throw new IllegalArgumentException("Goal requires an owning Project and Session");
    if(objective==null||objective.isBlank()||objective.length()>4096)throw new IllegalArgumentException("Objective must contain 1..4096 characters");
    validateFile(relativeFile);
    if(maxRuns<1||maxRuns>10||maxLlmCalls<1||maxLlmCalls>100)throw new IllegalArgumentException("Goal budgets: 1..10 Runs and 1..100 LLM calls");
    String id="goal-"+UUID.randomUUID();
    transaction.executeWithoutResult(status->{
      if(db.sql("INSERT INTO agent_goals(id,project,root,session,objective,file,digest,max_runs,max_calls,status) SELECT ?,?,?,?,?,?,?,?,?,'READY' WHERE (SELECT COUNT(*) FROM agent_goals WHERE project=? AND status NOT IN ('COMPLETED','CANCELLED'))<256")
          .params(id,owner.projectId(),owner.projectRoot().toString(),owner.conversationId(),objective,relativeFile,sha256.toLowerCase(Locale.ROOT),maxRuns,maxLlmCalls,owner.projectId()).update()!=1)
        throw new IllegalStateException("Project goal limit reached (256)");
      history(id,"READY","created");
      db.sql("UPDATE agent_goals SET max_tokens=? WHERE id=?").params(properties.getOutputLimit().getMaxTotalTokensPerGoal(),id).update();
    });return get(owner.projectId(),id);
  }
  private List<FileCriterion> criteria(String id,String file,String digest) {
    var items=db.sql("SELECT file,digest,json_pointer,expected_json FROM agent_goal_criteria WHERE goal=? ORDER BY ordinal").param(id)
        .query((rs,n)->new FileCriterion(rs.getString("file"),rs.getString("digest"),rs.getString("json_pointer"),rs.getString("expected_json"))).list();
    return items.isEmpty()?List.of(new FileCriterion(file,digest)):items;
  }
  public Goal create(AgentRunContext owner,String objective,List<FileCriterion> criteria,int maxRuns,int maxLlmCalls) {
    if(criteria==null||criteria.isEmpty()||criteria.size()>16)throw new IllegalArgumentException("Goal requires 1..16 file criteria");
    var normalized=new ArrayList<FileCriterion>();var paths=new HashSet<CriterionKey>();
    for(var item:criteria) {
      if(item==null)throw new IllegalArgumentException("File criterion is required");
      validateFile(item.relativeFile());var path=Path.of(item.relativeFile()).normalize();
      String relative=path.toString().replace('\\','/');
      if(!paths.add(new CriterionKey(path,item.jsonCriterion()?"json:"+item.jsonPointer():"digest")))throw new IllegalArgumentException("Duplicate completion criterion");
      if(item.jsonCriterion()) {
        if(item.sha256()!=null&&!item.sha256().isEmpty())throw new IllegalArgumentException("Use SHA-256 or JSON scalar criteria");
        var condition=JsonFileGoalCondition.parse(item.jsonPointer(),item.expectedJson());
        normalized.add(new FileCriterion(relative,"",condition.pointer(),condition.expectedJson()));
      } else {
        if(item.sha256()==null||!item.sha256().matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Expected SHA-256 must contain 64 hexadecimal characters");
        normalized.add(new FileCriterion(relative,item.sha256().toLowerCase(Locale.ROOT)));
      }
    }
    return transaction.execute(status->{
      var first=normalized.getFirst();var goal=createBase(owner,objective,first.relativeFile(),first.sha256(),maxRuns,maxLlmCalls);
      for(int i=0;i<normalized.size();i++) {
        var item=normalized.get(i);
        db.sql("INSERT INTO agent_goal_criteria(goal,ordinal,file,digest,json_pointer,expected_json) VALUES(?,?,?,?,?,?)")
            .params(goal.id(),i,item.relativeFile(),item.sha256(),item.jsonPointer(),item.expectedJson()).update();
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
    return transaction.execute(status->db.sql("SELECT * FROM agent_goals WHERE project=? AND id=?").params(project,id).query(ROW).optional()
        .orElseThrow(()->new IllegalArgumentException("Goal not found in this Project")));
  }
  public List<Goal> list(String project) {return transaction.execute(status->db.sql("SELECT * FROM agent_goals WHERE project=? ORDER BY id LIMIT 256").param(project).query(ROW).list());}
  public List<Goal> taskPage(String project,String root,String session,String after,int limit) {
    if(limit<1||limit>101)throw new IllegalArgumentException("Invalid projection page limit");
    return db.sql("SELECT * FROM agent_goals WHERE project=:project AND root=:root AND (:session IS NULL OR session=:session) AND id>:after ORDER BY id LIMIT :limit")
        .param("project",project).param("root",root).param("session",session).param("after",after).param("limit",limit).query(ROW).list();
  }
  public Optional<Goal> taskOrigin(String project,String root,String session,String run) {
    return db.sql("SELECT * FROM agent_goals WHERE project=:project AND root=:root AND session=:session AND (run=:run OR id IN (SELECT goal FROM agent_goal_attempts WHERE run=:run)) ORDER BY id LIMIT 1")
        .param("project",project).param("root",root).param("session",session).param("run",run).query(ROW).optional();
  }
  public Claim claim(String project,String id) {
    return transaction.execute(status->{
      get(project,id);String token=UUID.randomUUID().toString();
      var goal=db.sql("UPDATE agent_goals SET status='RUNNING',token=?,reason='' WHERE project=? AND id=? AND status IN ('READY','PAUSED','WAITING_APPROVAL','FAILED','BLOCKED') AND attempts<max_runs AND used<max_calls AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_pending=0 AND tokens_used<max_tokens)) RETURNING *")
          .params(token,project,id).query(ROW).optional().orElseThrow(()->new IllegalStateException("Goal is running, terminal, or its budget is exhausted"));
      history(id,"RUNNING","explicit_run");return new Claim(goal,token);
    });
  }
  public String beginAttempt(Claim claim) {
    return transaction.execute(status->{
      String run=UUID.randomUUID().toString();
      int count=db.sql("UPDATE agent_goals SET attempts=attempts+1,run=? WHERE id=? AND project=? AND token=? AND status='RUNNING' AND attempts<max_runs AND used<max_calls AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_pending=0 AND tokens_used<max_tokens))")
          .params(run,claim.goal().id(),claim.goal().projectId(),claim.token()).update();
      if(count!=1)throw new IllegalStateException("Goal claim inactive or budget exhausted");
      var goal=get(claim.goal().projectId(),claim.goal().id());
      db.sql("INSERT INTO agent_goal_attempts(goal,run,number,status) VALUES(?,?,?,'RUNNING')").params(goal.id(),run,goal.attempts()).update();return run;
    });
  }
  /** Charged before an LLM invocation; failed or uncertain calls never restore the reservation. */
  public boolean reserveLlm(Claim claim) {
    return db.sql("UPDATE agent_goals SET used=used+1,tokens_pending=tokens_pending+CASE WHEN max_tokens>0 THEN 1 ELSE 0 END WHERE id=? AND project=? AND token=? AND status='RUNNING' AND used<max_calls AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_used<max_tokens))")
        .params(claim.goal().id(),claim.goal().projectId(),claim.token()).update()==1;
  }
  public int remainingLlm(Claim claim) {
    return db.sql("SELECT max_calls-used FROM agent_goals WHERE id=? AND project=? AND token=? AND status='RUNNING' AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_used<max_tokens))")
        .params(claim.goal().id(),claim.goal().projectId(),claim.token()).query(Integer.class).optional().orElse(0);
  }
  /** One report for each reserved invocation; unknown usage is a durable, irreversible stop. */
  public void recordTotalTokens(Claim claim,String run,Integer tokens) {
    if(claim.goal().maxTotalTokens()==0)return;
    boolean known=tokens!=null&&tokens>0;
    int amount=known?tokens:0;
    var goal=transaction.execute(status->{
      int updated=db.sql("UPDATE agent_goals SET tokens_used=CASE WHEN tokens_used>9223372036854775807-? THEN 9223372036854775807 ELSE tokens_used+? END,tokens_unknown=CASE WHEN ? THEN tokens_unknown ELSE 1 END,tokens_pending=tokens_pending-1 WHERE id=? AND project=? AND token=? AND run=? AND status='RUNNING' AND tokens_pending>0")
          .params(amount,amount,known,claim.goal().id(),claim.goal().projectId(),claim.token(),run).update();
      if(updated!=1)throw new IllegalStateException("Goal usage report has no owned pending invocation");
      return get(claim.goal().projectId(),claim.goal().id());
    });
    if(goal.tokenUsageUnknown())throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    if(goal.totalTokens()>goal.maxTotalTokens())throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED);
  }
  public boolean tokenExhausted(Claim claim) {
    var goal=get(claim.goal().projectId(),claim.goal().id());
    return goal.maxTotalTokens()>0&&(goal.tokenUsageUnknown()||goal.totalTokens()>=goal.maxTotalTokens());
  }
  public dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation modelBudget(Claim claim,String run) {
    return new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation() {
      public boolean tryReserve(){return reserveLlm(claim);}
      public int remaining(){return remainingLlm(claim);}
      public boolean tokenLimitEnabled(){return claim.goal().maxTotalTokens()>0;}
      public boolean tokenExhausted(){return tokenLimitEnabled()&&GoalRepository.this.tokenExhausted(claim);}
      public boolean usageUnknown(){return tokenLimitEnabled()&&get(claim.goal().projectId(),claim.goal().id()).tokenUsageUnknown();}
      public void recordTotalTokens(Integer tokens){GoalRepository.this.recordTotalTokens(claim,run,tokens);}
    };
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
      if(db.sql("UPDATE agent_goals SET status=?,reason=?,token=NULL,tokens_unknown=CASE WHEN max_tokens>0 AND tokens_pending>0 THEN 1 ELSE tokens_unknown END WHERE id=? AND project=? AND token=? AND status='RUNNING'")
          .params(state,reason,claim.goal().id(),claim.goal().projectId(),claim.token()).update()!=1)throw new IllegalStateException("Goal claim inactive");
      history(claim.goal().id(),state,reason);
    });return get(claim.goal().projectId(),claim.goal().id());
  }
  public Goal verifiedWithoutRun(String project,String id) {
    transaction.executeWithoutResult(status->{
      var goal=get(project,id);
      String reason=goal.criteria().stream().anyMatch(FileCriterion::jsonCriterion)?"criteria_verified":"file_digest_verified";
      if(db.sql("UPDATE agent_goals SET status='COMPLETED',reason=? WHERE project=? AND id=? AND status NOT IN ('RUNNING','CANCELLED','COMPLETED')")
          .params(reason,project,id).update()!=1)throw new IllegalStateException("Goal is running or terminal");
      history(id,"COMPLETED",reason);
    });return get(project,id);
  }
  /** Human reconciliation only: unknown side effects remain unknown; no budget or evidence is restored. */
  public Goal reconcile(String project,String id,String expectedRunId) {
    transaction.executeWithoutResult(status->{
      get(project,id);
      if(db.sql("UPDATE agent_goals SET status='PAUSED',reason='uncertain_run_reconciled',token=NULL,tokens_unknown=CASE WHEN max_tokens>0 AND tokens_pending>0 THEN 1 ELSE tokens_unknown END WHERE project=? AND id=? AND status='RUNNING' AND run IS ?")
          .params(project,id,expectedRunId).update()!=1)throw new IllegalStateException("Goal is no longer running with the specified Run ID");
      db.sql("UPDATE agent_goal_attempts SET status='BLOCKED',reason='uncertain_run_reconciled' WHERE goal=? AND status='RUNNING'").param(id).update();
      history(id,"PAUSED","uncertain_run_reconciled");
    });return get(project,id);
  }
  public Goal cancel(String project,String id) {
    return cancel(project,id,null,null);
  }
  public Goal cancel(String project,String id,String expectedRun,long expectedAttempts) {
    return cancel(project,id,expectedRun,Long.valueOf(expectedAttempts));
  }
  private Goal cancel(String project,String id,String expectedRun,Long expectedAttempts) {
    transaction.executeWithoutResult(status->{
      get(project,id);
      if(db.sql("UPDATE agent_goals SET status='CANCELLED',reason='human_cancelled',token=NULL,tokens_unknown=CASE WHEN max_tokens>0 AND tokens_pending>0 THEN 1 ELSE tokens_unknown END WHERE project=:project AND id=:id AND status NOT IN ('COMPLETED','CANCELLED') AND (:attempts IS NULL OR (attempts=:attempts AND run IS :run))")
          .param("project",project).param("id",id).param("attempts",expectedAttempts).param("run",expectedRun).update()!=1) {
        if(expectedAttempts!=null)throw new dev.mikoto2000.rei.application.state.OperationConflictException();
        throw new IllegalStateException("Goal is already terminal");
      }
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
