package dev.mikoto2000.rei.core.policy;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Exact, expiring grants. Atomic SQL consumption happens before the side effect, never after it. */
@Repository
public class ToolApprovalRepository {
  private dev.mikoto2000.rei.timing.TimingRecorder timing;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setTiming(dev.mikoto2000.rei.timing.TimingRecorder timing){this.timing=timing;}
  private void observe(Request request,boolean begin,boolean approved) {
    try {if(timing==null||!timing.enabled())return;
      if(begin && request.status().equals("PENDING"))timing.beginSpan(request.runId(),request.id(),request.runId(),null,null,dev.mikoto2000.rei.timing.TimingRecorder.Category.APPROVAL_WAIT);
      else if(!begin)timing.endSpan(request.runId(),request.id(),approved?dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS:dev.mikoto2000.rei.timing.TimingRecorder.Status.FAILED);
    }catch(RuntimeException unavailable){/* Metrics cannot change grants or consumption. */}
  }
  public record Request(String id,String projectId,String sessionId,String runId,String tool,String argumentsPreview,
      String status,Instant expiresAt) {}
  private final JdbcClient db;
  private final Clock clock;
  public ToolApprovalRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    this.db=JdbcClient.create(source);this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS tool_approvals(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,tool TEXT NOT NULL,digest TEXT NOT NULL,preview TEXT NOT NULL,status TEXT NOT NULL,expires INTEGER NOT NULL,UNIQUE(project,session,run,tool,digest))").update();
    db.sql("CREATE INDEX IF NOT EXISTS tool_approvals_grants ON tool_approvals(project,session,tool,digest,status,expires)").update();
  }
  private static final org.springframework.jdbc.core.RowMapper<Request> ROW=(rs,n)->new Request(rs.getString("id"),rs.getString("project"),
      rs.getString("session"),rs.getString("run"),rs.getString("tool"),rs.getString("preview"),rs.getString("status"),Instant.ofEpochMilli(rs.getLong("expires")));
  public Request request(String tool,String input,AgentRunContext owner) {
    validate(owner);Objects.requireNonNull(tool);Objects.requireNonNull(input);
    if(input.length()>16384)throw new IllegalArgumentException("Approval arguments exceed 16384 characters; split the action before requesting approval");
    String digest=actionDigest(input,owner);long expires=clock.instant().plus(Duration.ofMinutes(15)).toEpochMilli();
    db.sql("INSERT OR IGNORE INTO tool_approvals VALUES(?,?,?,?,?,?,?,?,?)")
        .params(UUID.randomUUID().toString(),owner.projectId(),owner.conversationId(),owner.runId(),tool,digest,CredentialRedactor.redact(input),"PENDING",expires).update();
    var request=db.sql("SELECT * FROM tool_approvals WHERE project=? AND session=? AND run=? AND tool=? AND digest=?")
        .params(owner.projectId(),owner.conversationId(),owner.runId(),tool,digest).query(ROW).single();
    observe(request,true,false);return request;
  }
  public List<Request> list(String project) {
    return db.sql("SELECT * FROM tool_approvals WHERE project=? AND status IN ('PENDING','APPROVED') AND expires>? ORDER BY expires,id LIMIT 256")
        .params(project,clock.millis()).query(ROW).list();
  }
  public Request get(String project,String id) {
    return db.sql("SELECT * FROM tool_approvals WHERE project=? AND id=?").params(project,id).query(ROW).optional()
        .orElseThrow(()->new IllegalArgumentException("Approval request not found in this project"));
  }
  /** Only human-facing Shell/HTTP entry points call this method; it is not a model Tool. */
  public Request decide(String project,String id,boolean approved) {
    get(project,id);
    int changed=db.sql("UPDATE tool_approvals SET status=? WHERE project=? AND id=? AND status='PENDING' AND expires>?")
        .params(approved?"APPROVED":"DENIED",project,id,clock.millis()).update();
    if(changed!=1)throw new IllegalStateException("Approval is expired or already decided");
    var request=get(project,id);observe(request,false,approved);return request;
  }
  public boolean consume(String tool,String input,AgentRunContext owner) {
    validate(owner);
    // One SQL writer operation serializes concurrent clients and processes. Never reuse on callback failure.
    return db.sql("UPDATE tool_approvals SET status='CONSUMED' WHERE id=(SELECT id FROM tool_approvals WHERE project=? AND session=? AND tool=? AND digest=? AND status='APPROVED' AND expires>? ORDER BY expires,id LIMIT 1) AND status='APPROVED'")
        .params(owner.projectId(),owner.conversationId(),tool,actionDigest(input,owner),clock.millis()).update()==1;
  }
  private static void validate(AgentRunContext owner) {
    if(owner==null||owner.projectId()==null||owner.projectId().isBlank()||owner.conversationId().isBlank())
      throw new IllegalArgumentException("Approval requires an owning project and session");
  }
  private static String digest(String input) {
    try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
  }
  private static String actionDigest(String input,AgentRunContext owner) {return digest(owner.projectRoot()+"\u0000"+input);}
}
