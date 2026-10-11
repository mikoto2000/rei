package dev.mikoto2000.rei.conversation;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.storage.StorageDatabase;

/** Storage transaction commits Session, canonical Run, lease and receipt before dispatch. */
public final class ConversationAdmissionStore implements AutoCloseable {
  public static final Duration RECEIPT_RETENTION=Duration.ofDays(7);
  public static final int CAPACITY=100000;
  private static final Set<String> OPEN=java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final StorageDatabase database;
  private final Clock clock;
  private final String instance=UUID.randomUUID().toString();
  private boolean closed;
  private dev.mikoto2000.rei.event.AgentEventBus.Subscription subscription;
  public void observe(dev.mikoto2000.rei.event.AgentEventBus events) {
    subscription=events.subscribe(event->{
      if(event.runId()==null)return;
      RunStatus next=switch(event.type()) {
        case AGENT_RUN_STARTED->RunStatus.RUNNING;
        case AGENT_RUN_COMPLETED->RunStatus.COMPLETED;
        case AGENT_RUN_FAILED->RunStatus.FAILED;
        case AGENT_RUN_CANCELLED->RunStatus.CANCELLED;
        default->null;
      };
      if(next!=null)transition(event.runId(),next);
    });
  }
  public record Receipt(AgentRunContext context,boolean replay) {}
  private record Context(String runId,String sessionId,String root,String projectId,
      AgentRunContext.RequestSource source,AgentRunContext.Mode mode,boolean voiceInput,ResponseStyle responseStyle) {
    static Context of(AgentRunContext c){return new Context(c.runId(),c.conversationId(),c.projectRoot().toString(),c.projectId(),c.requestSource(),c.mode(),c.voiceInput(),c.responseStyle());}
    AgentRunContext value(){return new AgentRunContext(runId,sessionId,Path.of(root),projectId,source,mode,voiceInput,responseStyle);}
  }
  public ConversationAdmissionStore(Path root,Clock clock) {
    this.database=new StorageDatabase(root);this.clock=clock;OPEN.add(instance);
    try { recover(); } catch(RuntimeException error){OPEN.remove(instance);throw error;}
  }
  private void recover() {
    database.transaction(db->{
      var lost=new ArrayList<String>();
      try(var query=db.createStatement();var rows=query.executeQuery("SELECT run_id,owner_instance,owner_pid,owner_start FROM conversation_admissions WHERE active=1 OR status IN ('QUEUED','RUNNING')")) {
        while(rows.next()) {
          long pid=rows.getLong(3);String start=rows.getString(4),owner=rows.getString(2);
          boolean alive=start!=null&&ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(p->p.info().startInstant()).map(t->t.toString().equals(start)).orElse(false);
          if(!alive || pid==ProcessHandle.current().pid()&&!OPEN.contains(owner))lost.add(rows.getString(1));
        }
      }
      for(String id:lost)unknown(db,id);return null;
    });
  }
  public static String hash(String value) {
    try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
  }
  private static String keyHash(String key) {
    if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("Invalid Idempotency-Key");
    return hash(key);
  }
  private record SavedReceipt(String fingerprint,String runId,Instant expires) {}
  private SavedReceipt receipt(Connection db,String hash) throws SQLException {
    try(var query=db.prepareStatement("SELECT fingerprint,run_id,expires FROM chat_receipts WHERE key_hash=?")) {
      query.setString(1,hash);try(var rows=query.executeQuery()){return rows.next()?new SavedReceipt(rows.getString(1),rows.getString(2),Instant.parse(rows.getString(3))):null;}
    }
  }
  private void unexpired(SavedReceipt value){if(!clock.instant().isBefore(value.expires()))throw new IdempotencyExpiredException();}
  public Optional<RunSnapshot> lookup(String key) {
    String hash=keyHash(key);
    var value=database.read(db->receipt(db,hash));
    if(value==null)return Optional.empty();
    unexpired(value);
    return get(value.runId());
  }
  public Optional<RunSnapshot> get(String runId){return database.read(db->snapshot(db,runId));}
  private Optional<RunSnapshot> snapshot(Connection db,String id) throws Exception {
    try(var query=db.prepareStatement("SELECT context,status,started,completed FROM conversation_admissions WHERE run_id=?")) {
      query.setString(1,id);try(var rows=query.executeQuery()) {
        if(!rows.next())return Optional.empty();String json=rows.getString(1);
        if(json.length()>20000)throw new IllegalStateException("Admission context capacity exceeded");
        var context=StorageDatabase.JSON.readValue(json,Context.class).value();var status=RunStatus.valueOf(rows.getString(2));
        if(!context.runId().equals(id))throw new IllegalStateException("Admission Run identity mismatch");
        return Optional.of(new RunSnapshot(context,status,instant(rows.getString(3)),instant(rows.getString(4)),
            status==RunStatus.UNKNOWN?new RunFailure("OwnerLost","Inspect checkpoint before explicit resume"):
              status==RunStatus.FAILED?new RunFailure("ExecutionFailure","Agent run failed"):null));
      }
    }
  }
  private static Instant instant(String value){return value==null?null:Instant.parse(value);}
  public Receipt accept(SessionMetadata metadata,AgentRunContext context,String key,String fingerprint) {
    String hash=key==null?null:keyHash(key);
    if(hash!=null&&(fingerprint==null||!fingerprint.matches("[a-f0-9]{64}")))throw new IllegalArgumentException("Invalid request fingerprint");
    if(!metadata.sessionId().equals(context.conversationId())||!metadata.projectId().equals(context.projectId()))throw new IllegalArgumentException("Admission ownership mismatch");
    synchronized(database.writer) {
      if(closed)throw new IllegalStateException("Admission store closed");
      return database.transaction(db->{
        if(hash!=null) {
          var old=receipt(db,hash);
          if(old!=null){unexpired(old);if(!old.fingerprint().equals(fingerprint))throw new IdempotencyConflictException();return new Receipt(snapshot(db,old.runId()).orElseThrow().context(),true);}
          capacity(db,"chat_receipts");
        }
        reserve(db,context);
        long revision=1;SessionMetadata next=metadata;
        try(var query=db.prepareStatement("SELECT record,revision FROM sessions WHERE session_id=?")) {
          query.setString(1,metadata.sessionId());try(var rows=query.executeQuery()) {
            if(rows.next()) {
              var old=StorageDatabase.JSON.readValue(rows.getString(1),SessionMetadata.class);
              if(!old.projectId().equals(metadata.projectId())||!old.createdAt().equals(metadata.createdAt())||!old.title().equals(metadata.title()))throw new IllegalArgumentException("Immutable Session metadata");
              revision=Math.addExact(rows.getLong(2),1);next=metadata.touched(old.updatedAt());
            }
          }
        }
        SqliteSessionRepository.put(db,next,revision,null);
        if(hash!=null)try(var write=db.prepareStatement("INSERT INTO chat_receipts VALUES(?,?,?,?)")) {
          write.setString(1,hash);write.setString(2,fingerprint);write.setString(3,context.runId());write.setString(4,clock.instant().plus(RECEIPT_RETENTION).toString());write.executeUpdate();
        }
        return new Receipt(context,false);
      });
    }
  }
  private static void capacity(Connection db,String table)throws SQLException {
    // Table names are private constants, never caller input. Expired key tombstones are not reused.
    try(var query=db.createStatement();var rows=query.executeQuery("SELECT count(*) FROM "+table)) {
      if(rows.next()&&rows.getLong(1)>=CAPACITY)throw new java.util.concurrent.RejectedExecutionException("Durable conversation admission capacity reached");
    }
  }
  /** Checkpoint/Scheduler entry points also reserve known human Sessions before enqueue. */
  public void reserveKnownSession(AgentRunContext context) {
    database.transaction(db->{
      try(var query=db.prepareStatement("SELECT project_id FROM sessions WHERE session_id=?")) {
        query.setString(1,context.conversationId());try(var rows=query.executeQuery()) {
          if(rows.next()){if(!rows.getString(1).equals(context.projectId()))throw new SessionConflictException();reserve(db,context);}
        }
      }
      return null;
    });
  }
  private void reserve(Connection db,AgentRunContext context)throws Exception {
    var existing=snapshot(db,context.runId());
    if(existing.isPresent()) {
      if(!existing.get().context().equals(context))throw new IllegalArgumentException("Admission identity mismatch");
      return;
    }
    boolean deferred=false;
    try(var query=db.prepareStatement("SELECT run_id FROM conversation_admissions WHERE project_id=? AND session_id=? AND (active=1 OR status='QUEUED')")) {
      query.setString(1,context.projectId());query.setString(2,context.conversationId());try(var rows=query.executeQuery()) {
        if(rows.next()) {
          if(context.requestSource()==AgentRunContext.RequestSource.SHELL&&context.mode()==AgentRunContext.Mode.EXCLUSIVE)deferred=true;
          else throw new SessionBusyException(context.projectId(),context.conversationId(),rows.getString(1));
        }
      }
    }
    capacity(db,"conversation_admissions");
    String document=StorageDatabase.JSON.writeValueAsString(Context.of(context));
    if(document.length()>20000)throw new IllegalArgumentException("Admission context capacity exceeded");
    try(var write=db.prepareStatement("INSERT INTO conversation_admissions(run_id,project_id,session_id,context,status,active,owner_instance,owner_pid,owner_start,accepted) VALUES(?,?,?,?,'QUEUED',?,?,?,?,?)")) {
      write.setString(1,context.runId());write.setString(2,context.projectId());write.setString(3,context.conversationId());write.setString(4,document);
      write.setInt(5,deferred?0:1);write.setString(6,instance);write.setLong(7,ProcessHandle.current().pid());write.setString(8,ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null));write.setString(9,clock.instant().toString());write.executeUpdate();
    }
  }
  /** Shell's existing exclusive queue remains pending until the preceding runner has cleaned up. */
  public void startExecution(AgentRunContext context) {
    database.transaction(db->{
      var saved=snapshot(db,context.runId());if(saved.isEmpty())return null;
      if(!saved.get().context().equals(context)||saved.get().status().isTerminal())throw new IllegalStateException("Run cannot start");
      try(var query=db.prepareStatement("SELECT run_id FROM conversation_admissions WHERE project_id=? AND session_id=? AND active=1 AND run_id<>?")) {
        query.setString(1,context.projectId());query.setString(2,context.conversationId());query.setString(3,context.runId());
        try(var rows=query.executeQuery()){if(rows.next())throw new SessionBusyException(context.projectId(),context.conversationId(),rows.getString(1));}
      }
      try(var write=db.prepareStatement("UPDATE conversation_admissions SET active=1 WHERE run_id=? AND owner_instance=?")) {
        write.setString(1,context.runId());write.setString(2,instance);if(write.executeUpdate()!=1)throw new IllegalStateException("Run owner mismatch");
      }return null;
    });
  }
  public void transition(String id,RunStatus next) {
    database.transaction(db->{
      var old=snapshot(db,id);
      if(old.isEmpty()||old.get().status().isTerminal()||next==RunStatus.QUEUED)return null;
      try(var write=db.prepareStatement("UPDATE conversation_admissions SET status=?,started=COALESCE(started,?),completed=? WHERE run_id=? AND owner_instance=?")) {
        write.setString(1,next.name());write.setString(2,next==RunStatus.RUNNING?clock.instant().toString():null);
        write.setString(3,next.isTerminal()?clock.instant().toString():null);write.setString(4,id);write.setString(5,instance);write.executeUpdate();
      }return null;
    });
  }
  public void release(String id) {
    database.transaction(db->{
      try(var query=db.prepareStatement("SELECT owner_instance FROM conversation_admissions WHERE run_id=?")) {
        query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next()||!instance.equals(rows.getString(1)))return null;}
      }
      var old=snapshot(db,id);if(old.isEmpty())return null;
      if(!old.get().status().isTerminal())unknown(db,id);
      else try(var write=db.prepareStatement("UPDATE conversation_admissions SET active=0 WHERE run_id=? AND owner_instance=?")) {write.setString(1,id);write.setString(2,instance);write.executeUpdate();}
      return null;
    });
  }
  private void unknown(Connection db,String id)throws SQLException {
    try(var write=db.prepareStatement("UPDATE conversation_admissions SET status=CASE WHEN status IN ('QUEUED','RUNNING') THEN 'UNKNOWN' ELSE status END,active=0,completed=COALESCE(completed,?) WHERE run_id=?")) {
      write.setString(1,clock.instant().toString());write.setString(2,id);write.executeUpdate();
    }
  }
  @Override public void close() {
    synchronized(database.writer) {
      if(closed)return;closed=true;
      if(subscription!=null)subscription.unsubscribe();
      try {database.transaction(db->{var owned=new ArrayList<String>();try(var query=db.prepareStatement("SELECT run_id FROM conversation_admissions WHERE owner_instance=? AND (active=1 OR status IN ('QUEUED','RUNNING'))")){query.setString(1,instance);try(var rows=query.executeQuery()){while(rows.next())owned.add(rows.getString(1));}}for(String id:owned)unknown(db,id);return null;});}
      finally {OPEN.remove(instance);}
    }
  }
}
