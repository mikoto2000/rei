package dev.mikoto2000.rei.mcp;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Owned durable index only; canonical Run state comes from the existing admission store. */
public final class McpRequestStore {
  public record Request(String hash,String fingerprint,String project,String session,String run,Instant created,Instant expires,
      String result,Instant resultExpires,String state,int totalCriteria,List<String> criteria,String admissionError,Instant updatedAt) {}
  private McpCompletionEvidence evidence=new McpCompletionEvidence(null,null,null);
  private dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints;
  public void setEvidence(McpCompletionEvidence evidence){this.evidence=evidence;}
  public void setCheckpoints(dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints){this.checkpoints=checkpoints;}
  public Optional<dev.mikoto2000.rei.checkpoint.PersistentCheckpoint> checkpoint(Request r) {
    if(checkpoints==null)return Optional.empty();
    return checkpoints.findByRun(r.project(),r.run()).filter(c->c.sessionId().equals(r.session()));
  }
  private final StorageDatabase db;
  private final ConversationAdmissionStore admissions;
  private final ConversationTurnStore turns;
  private final Clock clock;
  private final Duration resultRetention,auditRetention;
  public McpRequestStore(java.nio.file.Path root,ConversationAdmissionStore admissions,ConversationTurnStore turns,Clock clock,
      Duration resultRetention,Duration auditRetention) {
    db=new StorageDatabase(root);this.admissions=admissions;this.turns=turns;this.clock=clock;
    this.resultRetention=resultRetention;this.auditRetention=auditRetention;
  }
  public byte[] cursorKey(){return db.read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT value FROM mcp_settings WHERE name='cursor_key'")){if(!r.next())throw new IllegalStateException("Cursor key missing");return HexFormat.of().parseHex(r.getString(1));}});}
  public Optional<Request> byRequest(String hash) {return query("request_hash",hash);}
  public Optional<Request> byRun(String run){return query("run_id",run);}
  private Optional<Request> query(String column,String value) {
    return db.read(c->{try(var q=c.prepareStatement("SELECT request_hash,fingerprint,project_id,session_id,run_id,created_at,expires_at,result_json,result_expires_at,terminal_state,total_criteria,criteria_json,admission_error,state_updated_at FROM mcp_requests WHERE principal='local-mcp' AND "+column+"=?")) {
      q.setString(1,value);try(var rows=q.executeQuery()){return rows.next()?Optional.of(new Request(rows.getString(1),rows.getString(2),rows.getString(3),rows.getString(4),rows.getString(5),Instant.parse(rows.getString(6)),Instant.parse(rows.getString(7)),rows.getString(8),rows.getString(9)==null?null:Instant.parse(rows.getString(9)),rows.getString(10),rows.getInt(11),Arrays.asList(StorageDatabase.JSON.readValue(rows.getString(12),String[].class)),rows.getString(13),rows.getString(14)==null?Instant.parse(rows.getString(6)):Instant.parse(rows.getString(14)))):Optional.empty();}
    }});
  }
  public boolean ownsSession(String project,String session) {
    return db.read(c->{try(var q=c.prepareStatement("SELECT 1 FROM mcp_sessions WHERE session_id=? AND project_id=? AND principal='local-mcp'")) {
      q.setString(1,session);q.setString(2,project);try(var rows=q.executeQuery()){return rows.next();}
    }});
  }
  public RunSnapshot status(Request request) {return admissions.get(request.run()).orElseThrow(()->new McpError("RUN_STATE_UNKNOWN"));}
  public void audit(String operation,String outcome,String project,String run,String hash,String correlation) {
    db.transaction(c->{try(var q=c.prepareStatement("INSERT INTO mcp_audit VALUES(?,'local-mcp',?,?,?,?,?,?,?)")) {
      q.setString(1,UUID.randomUUID().toString());q.setString(2,project);q.setString(3,run);q.setString(4,hash);
      q.setString(5,operation);q.setString(6,outcome);q.setString(7,clock.instant().toString());q.setString(8,correlation);q.executeUpdate();return null;
    }});
  }
  /** Snapshot only persisted final assistant output; tool logs and reasoning are never projected. */
  public void snapshot(Request request) {
    if(request.state()!=null)return;
    var run=status(request);if(!run.status().isTerminal())return;
    var turn=turns.findRun(request.session(),request.run());
    if(turn.isPresent()&&turn.get().status()==ConversationTurnStore.Status.RUNNING)return;
    if(turn.isEmpty()&&run.status()==RunStatus.COMPLETED)return;
    String content=CredentialRedactor.redact(turn.map(ConversationTurnStore.Turn::assistantMessage).orElse(""));
    var result=new LinkedHashMap<String,Object>();result.put("content",content);result.put("artifacts",evidence.artifacts(request));
    result.put("completion",evidence.completion(request,run.status()==RunStatus.COMPLETED));
    result.put("unresolved_items",((Number)((Map<?,?>)result.get("completion")).get("verified_criteria")).intValue()<request.totalCriteria()?List.of("Acceptance criteria have no saved verification evidence"):List.of());
    try {
      String json=StorageDatabase.JSON.writeValueAsString(result);
      db.transaction(c->{try(var q=c.prepareStatement("UPDATE mcp_requests SET result_json=?,result_expires_at=?,terminal_state=? WHERE run_id=? AND terminal_state IS NULL")) {
        q.setString(1,json);q.setString(2,(run.completedAt()==null?clock.instant():run.completedAt()).plus(resultRetention).toString());q.setString(3,run.status().name());q.setString(4,request.run());
        if(q.executeUpdate()==1)auditInTransaction(c,request,run.status().name());return null;
      }});
    }catch(java.io.IOException error){throw new IllegalStateException("Cannot save MCP result",error);}
  }
  private void auditInTransaction(java.sql.Connection c,Request request,String state)throws java.sql.SQLException {
    try(var q=c.prepareStatement("INSERT INTO mcp_audit VALUES(?,'local-mcp',?,?,?,?,?,?,?)")) {
      q.setString(1,UUID.randomUUID().toString());q.setString(2,request.project());q.setString(3,request.run());q.setString(4,request.hash());q.setString(5,"run_terminal");q.setString(6,state);q.setString(7,clock.instant().toString());q.setString(8,UUID.randomUUID().toString());q.executeUpdate();
    }
  }
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
  public void captureCompleted() {
    var ids=db.read(c->{var result=new ArrayList<String>();try(var q=c.createStatement();var rows=q.executeQuery("SELECT run_id FROM mcp_requests WHERE terminal_state IS NULL LIMIT 100")){while(rows.next())result.add(rows.getString(1));}return result;});
    for(String id:ids)byRun(id).ifPresent(this::snapshot);
  }
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
  public void expire() {
    var expired=db.read(c->{var ids=new ArrayList<String>();try(var q=c.prepareStatement("SELECT run_id FROM mcp_requests WHERE result_json IS NOT NULL AND result_expires_at<=?")){q.setString(1,clock.instant().toString());try(var r=q.executeQuery()){while(r.next())ids.add(r.getString(1));}}return ids;});for(var id:expired)byRun(id).ifPresent(r->audit("result_expired","RESULT_EXPIRED",r.project(),r.run(),r.hash(),UUID.randomUUID().toString()));
    db.transaction(c->{try(var q=c.prepareStatement("UPDATE mcp_requests SET result_json=NULL WHERE result_expires_at<=?")) {q.setString(1,clock.instant().toString());q.executeUpdate();}
      try(var q=c.prepareStatement("UPDATE mcp_requests SET criteria_json='[]' WHERE expires_at<=? AND terminal_state IS NOT NULL AND result_json IS NULL")){q.setString(1,clock.instant().toString());q.executeUpdate();}
      try(var q=c.prepareStatement("DELETE FROM mcp_audit WHERE created_at<?")){q.setString(1,clock.instant().minus(auditRetention).toString());q.executeUpdate();}return null;});
  }
}
