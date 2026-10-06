package dev.mikoto2000.rei.externalagent;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Durable review outcomes and opt-in provider UUIDs, never prompts, source contents or process logs. */
@Repository
public class ExternalReviewRepository {
  public record Review(String id,String projectId,String projectRoot,String sessionId,String runId,String target,
      String previousId,String status,Instant createdAt,Instant completedAt,ExternalAgentResult result) { }
  private final JdbcClient db;
  private final Clock clock;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  public ExternalReviewRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS external_reviews(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,target TEXT,previous TEXT,status TEXT NOT NULL,created INTEGER NOT NULL,completed INTEGER,result TEXT)").update();
    db.sql("CREATE INDEX IF NOT EXISTS external_reviews_project ON external_reviews(project,created)").update();
    db.sql("CREATE TABLE IF NOT EXISTS external_review_continuations(previous TEXT PRIMARY KEY,review TEXT NOT NULL)").update();
  }
  /** An attempted continuation consumes its parent even after failure or a crash. Never replay it. */
  public void startContinuation(AgentRunContext owner,String id,Path root,String target,String previous) {
    var parent=get(owner.projectId(),previous);
    if(!parent.projectRoot().equals(root.toString()) || parent.result()==null || !parent.result().success()
        || !ExternalAgentResult.validSessionId(parent.result().externalSessionId()))throw new IllegalArgumentException("Resumable completed review required");
    if(db.sql("INSERT OR IGNORE INTO external_review_continuations(previous,review) VALUES(?,?)").params(previous,id).update()!=1)
      throw new IllegalArgumentException("This review's continuation was already attempted; use its completed successor or request a fresh re-review");
    start(owner,id,root,target,previous);
  }
  public boolean continuationAttempted(String previous) {
    return db.sql("SELECT count(*) FROM external_review_continuations WHERE previous=?").param(previous).query(Integer.class).single()>0;
  }
  public void start(AgentRunContext owner,String id,Path root,String target,String previous) {
    if(owner.projectId()==null || owner.projectId().isBlank())throw new IllegalArgumentException("Project required");
    if(previous!=null) {
      var parent=get(owner.projectId(),previous);
      if(!parent.projectRoot().equals(root.toString()) || parent.status().equals("STARTED"))throw new IllegalArgumentException("Completed review in the same project root required");
    }
    db.sql("INSERT INTO external_reviews VALUES(?,?,?,?,?,?,?,'STARTED',?,NULL,NULL)")
        .params(id,owner.projectId(),root.toString(),owner.conversationId(),owner.runId(),target,previous,clock.millis()).update();
  }
  public void finish(String project,String id,ExternalAgentResult result) {
    var safe=safe(result);
    String json;
    try{json=mapper.writeValueAsString(safe);}catch(Exception error){throw new IllegalStateException("Review serialization failed");}
    if(db.sql("UPDATE external_reviews SET status=?,completed=?,result=? WHERE project=? AND id=? AND status='STARTED'")
        .params(safe.status().name(),clock.millis(),json,project,id).update()!=1)throw new IllegalArgumentException("Review is missing or already terminal");
  }
  public Review get(String project,String id) {
    return db.sql("SELECT * FROM external_reviews WHERE project=? AND id=?").params(project,id).query(this::row).optional()
        .orElseThrow(()->new IllegalArgumentException("Review not found in this Project"));
  }
  public List<Review> list(String project) {return db.sql("SELECT * FROM external_reviews WHERE project=? ORDER BY created DESC,id LIMIT 20").param(project).query(this::row).list();}
  private Review row(java.sql.ResultSet rs,int index)throws java.sql.SQLException {
    String json=rs.getString("result");ExternalAgentResult result=null;
    if(json!=null)try{result=mapper.readValue(json,ExternalAgentResult.class);}catch(Exception error){throw new IllegalStateException("Stored review is invalid");}
    Long completed=rs.getObject("completed")==null?null:rs.getLong("completed");
    return new Review(rs.getString("id"),rs.getString("project"),rs.getString("root"),rs.getString("session"),rs.getString("run"),rs.getString("target"),
        rs.getString("previous"),rs.getString("status"),Instant.ofEpochMilli(rs.getLong("created")),completed==null?null:Instant.ofEpochMilli(completed),result);
  }
  static ExternalAgentResult safe(ExternalAgentResult result) {
    boolean truncated=result.findings().size()>24 || result.warnings().size()>12 || length(result.summary())>2048
        || result.findings().stream().anyMatch(f->length(f.title())>256 || length(f.reason())>1024 || length(f.recommendation())>1024 || length(f.location())>512)
        || result.warnings().stream().anyMatch(s->length(s)>512);
    var findings=result.findings().stream().limit(24).map(f->new ExternalAgentFinding(f.severity()==null?ExternalAgentFinding.Severity.info:f.severity(),
        bounded(f.title(),256),bounded(f.reason(),1024),bounded(f.recommendation(),1024),bounded(f.location(),512))).toList();
    var warnings=new ArrayList<>(result.warnings().stream().limit(truncated?11:12).map(s->bounded(s,512)).toList());
    if(truncated)warnings.add("Saved review fields were truncated; inspect the review scope before drawing conclusions");
    var status=truncated && result.status()==ExternalAgentResult.Status.SUCCESS?ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS:result.status();
    return new ExternalAgentResult(status,bounded(result.summary(),2048),findings,warnings,result.duration(),result.exitCode(),"",result.reviewId(),result.externalSessionId());
  }
  private static int length(String text){return text==null?0:text.length();}
  private static String bounded(String text,int limit){return ExternalAgentDelegationService.bounded(text,limit);}
}
