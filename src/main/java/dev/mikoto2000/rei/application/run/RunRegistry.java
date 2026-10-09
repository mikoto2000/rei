package dev.mikoto2000.rei.application.run;

import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Monitor-protected transitions publish an immutable snapshot of status and metadata together. */
public final class RunRegistry implements AutoCloseable {
  public static final Duration RETENTION = Duration.ofMinutes(30);
  private final Clock clock;
  private final Map<String, RunSnapshot> runs = new LinkedHashMap<>();
  private final org.springframework.jdbc.core.simple.JdbcClient db;
  private final String instance=UUID.randomUUID().toString();
  private final Map<String,String> documents=new HashMap<>();
  private final Set<String> owned=new HashSet<>();
  private final Set<String> restored=new HashSet<>();
  private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper()
      .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  private record StoredRun(int version,String runId,String sessionId,String root,String projectId,
      AgentRunContext.RequestSource source,AgentRunContext.Mode mode,RunStatus status,Instant startedAt,Instant completedAt,RunFailure failure,
      long ownerPid,String ownerStart,String ownerInstance,RunSnapshot.ChildOrigin childOrigin,boolean voiceInput) {
    static StoredRun of(RunSnapshot value,String instance) {
      var c=value.context();return new StoredRun(1,c.runId(),c.conversationId(),c.projectRoot().toString(),c.projectId(),
          c.requestSource(),c.mode(),value.status(),value.startedAt(),value.completedAt(),value.failure(),
          ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null),instance,value.childOrigin(),c.voiceInput());
    }
    boolean ownerAlive() {
      return ownerPid>0 && ownerStart!=null && ProcessHandle.of(ownerPid).filter(ProcessHandle::isAlive)
          .flatMap(handle->handle.info().startInstant()).map(instant->instant.toString().equals(ownerStart)).orElse(false);
    }
    RunSnapshot snapshot() {
      if(version!=1 || status==null)throw new IllegalStateException("Unsupported Run schema");
      return new RunSnapshot(new AgentRunContext(runId,sessionId,java.nio.file.Path.of(root),projectId,source,mode,voiceInput),status,startedAt,completedAt,failure,childOrigin);
    }
  }
  public RunRegistry(Clock clock) { this(clock,null); }
  public RunRegistry(Clock clock,javax.sql.DataSource source) {
    this.clock=clock;this.db=source==null?null:org.springframework.jdbc.core.simple.JdbcClient.create(source);
    if(db==null)return;
    db.sql("CREATE TABLE IF NOT EXISTS rei_run_registry (run_id TEXT PRIMARY KEY, snapshot TEXT NOT NULL)").update();
    for(String document:db.sql("SELECT snapshot FROM rei_run_registry ORDER BY run_id").query(String.class).list()) {
      try {
        if(document.length()>20000)throw new IllegalStateException("Run snapshot capacity exceeded");
        var stored=json.readValue(document,StoredRun.class);var value=stored.snapshot();
        documents.put(value.context().runId(),document);
        if(!value.status().isTerminal() && !stored.ownerAlive()) {
          value=unknown(value);save(value);
        }
        runs.put(value.context().runId(),value);
        restored.add(value.context().runId());
      }catch(java.io.IOException error){throw new IllegalStateException("Cannot restore Run registry",error);}
    }
    purgeExpired();
  }
  private void save(RunSnapshot value) {
    if(db==null)return;
    try {
      String document=json.writeValueAsString(StoredRun.of(value,instance));
      if(document.length()>20000)throw new IllegalArgumentException("Run snapshot capacity exceeded");
      String id=value.context().runId(),previous=documents.get(id);
      if(previous==null)db.sql("INSERT INTO rei_run_registry(run_id,snapshot) VALUES(:id,:snapshot)").param("id",id).param("snapshot",document).update();
      else if(db.sql("UPDATE rei_run_registry SET snapshot=:snapshot WHERE run_id=:id AND snapshot=:previous")
          .param("id",id).param("snapshot",document).param("previous",previous).update()!=1)
        throw new dev.mikoto2000.rei.application.state.OperationConflictException();
      documents.put(id,document);
    }catch(java.io.IOException error){throw new IllegalStateException("Cannot save Run registry",error);}
  }
  private void delete(String runId) {
    if(db!=null && documents.containsKey(runId)) {
      if(db.sql("DELETE FROM rei_run_registry WHERE run_id=:id AND snapshot=:previous").param("id",runId).param("previous",documents.get(runId)).update()!=1)
        throw new dev.mikoto2000.rei.application.state.OperationConflictException();
      documents.remove(runId);
    }
  }
  private RunSnapshot unknown(RunSnapshot value) {
    return new RunSnapshot(value.context(),RunStatus.UNKNOWN,value.startedAt(),clock.instant(),new RunFailure("OwnerLost","Execution result unknown; inspect checkpoint before explicit resume"),value.childOrigin());
  }
  @Override public synchronized void close() {
    if(db==null)return;
    for(String run:owned) {
      var value=runs.get(run);
      if(value!=null && !value.status().isTerminal()){value=unknown(value);save(value);runs.put(run,value);}
    }
    owned.clear();
  }
  /** Roll back a synchronous submission failure before returning acceptance to the caller. */
  public synchronized void forget(String runId) {
    var value=runs.get(runId);
    if(db!=null && value!=null && !value.status().isTerminal() && !owned.contains(runId))throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    delete(runId);runs.remove(runId);owned.remove(runId);restored.remove(runId);
  }

  public synchronized void register(AgentRunContext context) {
    if(runs.containsKey(context.runId()))throw new IllegalArgumentException("Duplicate run");
    var value=new RunSnapshot(context,RunStatus.QUEUED,null,null,null);save(value);runs.put(context.runId(),value);owned.add(context.runId());
  }
  /** Delegation retains the existing child Run, with the human owner's Session as metadata. */
  public synchronized void registerChild(AgentRunContext child,AgentRunContext parent,String agent) {
    registerChild(child,parent,agent,null);
  }
  public synchronized void registerChild(AgentRunContext child,AgentRunContext parent,String agent,String durableTaskId) {
    if(durableTaskId!=null && !durableTaskId.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Invalid durable child reference");
    if(!Objects.equals(child.projectId(),parent.projectId())||!child.projectRoot().equals(parent.projectRoot())
        ||child.runId().equals(parent.runId())||parent.projectId()==null||agent==null||agent.isBlank()||agent.length()>128)
      throw new IllegalArgumentException("Invalid child ownership");
    var owner=get(parent.runId());
    if(!owner.context().equals(parent)||!ownsExecution(parent.runId())||owner.status().isTerminal())
      throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    if(runs.containsKey(child.runId()))throw new IllegalArgumentException("Duplicate run");
    var origin=new RunSnapshot.ChildOrigin(parent.runId(),owner.childOrigin()==null?parent.conversationId():owner.childOrigin().sessionId(),agent,durableTaskId);
    var value=new RunSnapshot(child,RunStatus.QUEUED,null,null,null,origin);save(value);runs.put(child.runId(),value);owned.add(child.runId());
  }
  public synchronized RunSnapshot get(String runId) {
    if(db!=null&&!owned.contains(runId)) {
      var document=db.sql("SELECT snapshot FROM rei_run_registry WHERE run_id=:id").param("id",runId).query(String.class).optional();
      if(document.isEmpty()){runs.remove(runId);documents.remove(runId);restored.remove(runId);throw new RunNotFoundException();}
      if(!document.get().equals(documents.get(runId)) || !runs.containsKey(runId) || !runs.get(runId).status().isTerminal()) {
        try {
          if(document.get().length()>20000)throw new IllegalStateException("Run snapshot capacity exceeded");
          var stored=json.readValue(document.get(),StoredRun.class);var refreshed=stored.snapshot();
          if(!runId.equals(refreshed.context().runId()))throw new IllegalStateException("Run identity mismatch");
          documents.put(runId,document.get());
          if(!refreshed.status().isTerminal()&&!stored.ownerAlive()){refreshed=unknown(refreshed);save(refreshed);}
          runs.put(runId,refreshed);restored.add(runId);
        }catch(java.io.IOException error){throw new IllegalStateException("Cannot refresh Run registry",error);}
      }
    }
    var snapshot = runs.get(runId);
    if (snapshot == null) throw new RunNotFoundException();
    return snapshot;
  }
  public synchronized boolean transition(String runId, RunStatus next, RunFailure failure) {
    var current = get(runId);
    if (current.status().isTerminal() || next == RunStatus.QUEUED || next == current.status()) return false;
    if(db!=null && !owned.contains(runId))throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    if (current.status() == RunStatus.QUEUED && next != RunStatus.RUNNING && next != RunStatus.CANCELLED) return false;
    var value=new RunSnapshot(current.context(), next,
        next == RunStatus.RUNNING ? clock.instant() : current.startedAt(),
        next.isTerminal() ? clock.instant() : null, next == RunStatus.FAILED || next == RunStatus.UNKNOWN ? failure : null,current.childOrigin());
    save(value);runs.put(runId,value);
    return true;
  }
  public synchronized List<String> purgeExpired() {
    var expired = runs.entrySet().stream().filter(entry -> entry.getValue().completedAt() != null
        && !clock.instant().isBefore(entry.getValue().completedAt().plus(RETENTION))).map(Map.Entry::getKey).toList();
    expired.forEach(this::forget);
    return expired;
  }
  public synchronized Set<String> runIds() {
    return db==null?Set.copyOf(runs.keySet()):Set.copyOf(db.sql("SELECT run_id FROM rei_run_registry").query(String.class).list());
  }
  /** Restored metadata has no event subscription history in this server instance. */
  public synchronized boolean restored(String runId) { return restored.contains(runId); }
  public synchronized boolean ownsExecution(String runId) { return db==null || owned.contains(runId); }
}
