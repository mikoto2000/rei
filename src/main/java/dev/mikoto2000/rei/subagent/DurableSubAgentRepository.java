package dev.mikoto2000.rei.subagent;

import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Durable metadata for the existing child runner. Reservations and Tool starts precede execution. */
public final class DurableSubAgentRepository {
  public record Operation(String id,String tool,String argumentsHash,String status,String reconciliation) {}
  public record Checkpoint(int version,String id,long revision,String project,String root,String session,String parentRun,
      String agent,String task,String context,String baseline,String status,String run,int maxCalls,long maxTokens,
      int consumedCalls,long consumedTokens,boolean usageUnknown,boolean modelPending,List<Operation> operations,String result,String resultHash,
      Instant updated,long ownerPid,String ownerStart) {
    public Checkpoint {operations=List.copyOf(operations);}
  }
  private final JdbcClient db;private final TransactionTemplate transaction;private final Clock clock;
  private final int maxBytes;
  private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
  public DurableSubAgentRepository(DataSource source,Clock clock) {
    this(source,clock,8388608);
  }
  DurableSubAgentRepository(DataSource source,Clock clock,int maxBytes) {
    if(maxBytes<1024 || maxBytes>8388608)throw new IllegalArgumentException("Invalid durable child storage limit");this.maxBytes=maxBytes;
    this.db=JdbcClient.create(source);this.clock=clock;transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    db.sql("CREATE TABLE IF NOT EXISTS subagent_checkpoints(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,snapshot TEXT NOT NULL)").update();
    for(var saved:db.sql("SELECT snapshot FROM subagent_checkpoints ORDER BY id LIMIT 1025").query(String.class).list()) {
      var state=decode(saved);if(state.status().equals("RUNNING") && !alive(state))ownerLost(state.id(),state.run());
    }
  }
  public synchronized Checkpoint create(AgentRunContext owner,String agent,String task,String context,int calls,long tokens,String baseline) {
    identity(owner);if(agent==null || !agent.matches("[a-z][a-z0-9-]{0,63}") || task==null || task.isBlank() || task.length()>16384
        || context!=null && context.length()>32768 || calls<1 || calls>1000 || tokens<0 || baseline==null || baseline.length()>256)throw new IllegalArgumentException("Invalid durable child request");
    var state=new Checkpoint(1,UUID.randomUUID().toString(),0,owner.projectId(),root(owner),owner.conversationId(),owner.runId(),agent,
        CredentialRedactor.redact(task),context==null?null:CredentialRedactor.redact(context),baseline,"QUEUED",null,calls,tokens,0,0,false,false,List.of(),null,null,clock.instant(),0,null);
    String encoded=encode(state);return transaction.execute(tx->{
      if(db.sql("SELECT COUNT(*) FROM subagent_checkpoints").query(Long.class).single()>=1024
          || db.sql("SELECT COALESCE(SUM(length(CAST(snapshot AS BLOB))),0) FROM subagent_checkpoints").query(Long.class).single()+encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes)throw new IllegalArgumentException("Durable child storage capacity reached");
      db.sql("INSERT INTO subagent_checkpoints(id,revision,snapshot) VALUES(?,0,?)").params(state.id(),encode(state)).update();return state;
    });
  }
  public Checkpoint get(AgentRunContext owner,String id){identity(owner);var state=load(id);
    if(!state.project().equals(owner.projectId()) || !state.root().equals(root(owner)) || !state.session().equals(owner.conversationId()))throw new IllegalArgumentException("Child belongs to another human Project/root/session");return state;
  }
  public List<Checkpoint> list(AgentRunContext owner,int offset,int limit){identity(owner);if(offset<0 || offset>1024 || limit<1 || limit>100)throw new IllegalArgumentException("Invalid child page");
    return db.sql("SELECT snapshot FROM subagent_checkpoints WHERE json_extract(snapshot,'$.project')=? AND json_extract(snapshot,'$.root')=? AND json_extract(snapshot,'$.session')=? ORDER BY id LIMIT ? OFFSET ?")
        .params(owner.projectId(),root(owner),owner.conversationId(),limit,offset).query(String.class).list().stream().map(this::decode).toList();
  }
  public synchronized Checkpoint claim(AgentRunContext owner,String id,long revision,String run){var saved=get(owner,id);
    if(saved.revision()!=revision || run==null || run.isBlank() || run.length()>128 || !Set.of("QUEUED","UNKNOWN","FAILED","CANCELLED","TIMEOUT").contains(saved.status())
        || saved.operations().stream().anyMatch(o->Set.of("STARTED","UNKNOWN").contains(o.status())) || exhausted(saved))throw new IllegalArgumentException("Child requires current revision, reconciliation and remaining budget");
    return update(saved,s->copy(s,"RUNNING",run,s.consumedCalls(),s.consumedTokens(),s.usageUnknown(),s.operations(),null,null,ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null)));
  }
  public synchronized boolean reserve(String id,String run){var saved=owned(id,run);if(exhausted(saved))return false;
    update(saved,s->pending(copy(s,s.status(),run,s.consumedCalls()+1,s.consumedTokens(),s.usageUnknown(),s.operations(),s.result(),s.resultHash(),s.ownerPid(),s.ownerStart()),true));return true;
  }
  public synchronized void tokens(String id,String run,Integer tokens){var saved=owned(id,run);if(tokens!=null && tokens<0)throw new IllegalArgumentException("Invalid token usage");
    update(saved,s->pending(copy(s,s.status(),run,s.consumedCalls(),tokens==null?s.consumedTokens():Math.addExact(s.consumedTokens(),tokens),s.usageUnknown() || tokens==null || tokens==0,
        s.operations(),s.result(),s.resultHash(),s.ownerPid(),s.ownerStart()),false));
  }
  public synchronized void toolStarted(String id,String run,String operation,String tool,String hash){var saved=owned(id,run);
    if(operation==null || operation.length()>128 || tool==null || tool.length()>128 || hash==null || hash.length()>128 || saved.operations().size()>=64
        || saved.operations().stream().anyMatch(o->o.id().equals(operation)))throw new IllegalArgumentException("Invalid or duplicate child Tool operation");
    var operations=new ArrayList<>(saved.operations());operations.add(new Operation(operation,tool,hash,"STARTED",null));
    update(saved,s->copy(s,s.status(),run,s.consumedCalls(),s.consumedTokens(),s.usageUnknown(),operations,s.result(),s.resultHash(),s.ownerPid(),s.ownerStart()));
  }
  public synchronized void toolCompleted(String id,String run,String operation,boolean success){var saved=owned(id,run);
    if(saved.operations().stream().noneMatch(o->o.id().equals(operation) && o.status().equals("STARTED")))throw new IllegalArgumentException("No started child Tool operation");
    var operations=saved.operations().stream().map(o->o.id().equals(operation)?new Operation(o.id(),o.tool(),o.argumentsHash(),success?"SUCCEEDED":"UNKNOWN",null):o).toList();
    update(saved,s->copy(s,s.status(),run,s.consumedCalls(),s.consumedTokens(),s.usageUnknown(),operations,s.result(),s.resultHash(),s.ownerPid(),s.ownerStart()));
  }
  public synchronized void complete(String id,String run,String status,String result){var saved=owned(id,run);
    if(!Set.of("COMPLETED","FAILED","CANCELLED","TIMEOUT").contains(status) || result!=null && result.length()>65536)throw new IllegalArgumentException("Invalid child result");
    boolean unknown=saved.operations().stream().anyMatch(o->Set.of("STARTED","UNKNOWN").contains(o.status()));
    String retained=result==null?null:CredentialRedactor.redact(result);
    update(saved,s->copy(s,unknown?"UNKNOWN":status,run,s.consumedCalls(),s.consumedTokens(),s.usageUnknown() || s.modelPending() && s.maxTokens()>0,unknown(s.operations()),retained,retained==null?null:hash(retained),0,null));
  }
  public synchronized void ownerLost(String id,String run){var saved=owned(id,run);update(saved,s->copy(s,"UNKNOWN",run,s.consumedCalls(),s.consumedTokens(),s.usageUnknown() || s.modelPending() && s.maxTokens()>0,unknown(s.operations()),s.result(),s.resultHash(),0,null));}
  public synchronized Checkpoint reconcile(AgentRunContext owner,String id,long revision,String operation,String status,String note){var saved=get(owner,id);
    if(owner.mode()!=AgentRunContext.Mode.EXCLUSIVE)throw new IllegalArgumentException("Exclusive human Run required for reconciliation");
    if(saved.revision()!=revision || !saved.status().equals("UNKNOWN") || !Set.of("SUCCEEDED","FAILED").contains(status) || note==null || note.isBlank() || note.length()>1000
        || saved.operations().stream().noneMatch(o->o.id().equals(operation) && o.status().equals("UNKNOWN")))throw new IllegalArgumentException("Current unknown operation and explicit human observation required");
    var operations=saved.operations().stream().map(o->o.id().equals(operation)?new Operation(o.id(),o.tool(),o.argumentsHash(),status,CredentialRedactor.redact(note)):o).toList();
    return update(saved,s->copy(s,s.status(),s.run(),s.consumedCalls(),s.consumedTokens(),s.usageUnknown(),operations,s.result(),s.resultHash(),0,null));
  }
  private Checkpoint owned(String id,String run){var state=load(id);if(!state.status().equals("RUNNING") || !Objects.equals(state.run(),run))throw new IllegalArgumentException("Child execution owner changed");return state;}
  private Checkpoint load(String id){if(id==null || !id.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("Invalid child ID");return decode(db.sql("SELECT snapshot FROM subagent_checkpoints WHERE id=?").param(id).query(String.class).optional().orElseThrow(()->new IllegalArgumentException("Child not found")));}
  private Checkpoint update(Checkpoint saved,UnaryOperator<Checkpoint> change){var next=change.apply(saved);String encoded=encode(next);return transaction.execute(tx->{
    var current=load(saved.id());if(current.revision()!=saved.revision())throw new ConcurrentModificationException("Child checkpoint revision changed");
    long size=db.sql("SELECT COALESCE(SUM(length(CAST(snapshot AS BLOB))),0) FROM subagent_checkpoints").query(Long.class).single();
    if(size-encode(current).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>maxBytes)throw new IllegalArgumentException("Durable child storage capacity reached");
    if(db.sql("UPDATE subagent_checkpoints SET revision=?,snapshot=? WHERE id=? AND revision=?").params(next.revision(),encoded,saved.id(),saved.revision()).update()!=1)throw new ConcurrentModificationException("Child checkpoint revision changed");return next;});}
  private Checkpoint copy(Checkpoint s,String status,String run,int calls,long tokens,boolean unknown,List<Operation> operations,String result,String hash,long pid,String start){return new Checkpoint(1,s.id(),s.revision()+1,s.project(),s.root(),s.session(),s.parentRun(),s.agent(),s.task(),s.context(),s.baseline(),status,run,s.maxCalls(),s.maxTokens(),calls,tokens,unknown,s.modelPending(),operations,result,hash,clock.instant(),pid,start);}
  private Checkpoint pending(Checkpoint s,boolean pending){return new Checkpoint(s.version(),s.id(),s.revision(),s.project(),s.root(),s.session(),s.parentRun(),s.agent(),s.task(),s.context(),s.baseline(),s.status(),s.run(),s.maxCalls(),s.maxTokens(),s.consumedCalls(),s.consumedTokens(),s.usageUnknown(),pending,s.operations(),s.result(),s.resultHash(),s.updated(),s.ownerPid(),s.ownerStart());}
  private static boolean exhausted(Checkpoint s){return s.consumedCalls()>=s.maxCalls() || s.maxTokens()>0 && (s.usageUnknown() || s.consumedTokens()>=s.maxTokens());}
  private static List<Operation> unknown(List<Operation> operations){return operations.stream().map(o->o.status().equals("STARTED")?new Operation(o.id(),o.tool(),o.argumentsHash(),"UNKNOWN",null):o).toList();}
  private static boolean alive(Checkpoint s){return s.ownerPid()>0 && s.ownerStart()!=null && ProcessHandle.of(s.ownerPid()).filter(ProcessHandle::isAlive).flatMap(h->h.info().startInstant()).map(i->i.toString().equals(s.ownerStart())).orElse(false);}
  private static void identity(AgentRunContext owner){if(owner==null || owner.projectId()==null || owner.conversationId().startsWith("subagent:"))throw new IllegalArgumentException("Current human Project owner required");}
  private static String root(AgentRunContext owner){try{return owner.projectRoot().toRealPath().toString();}catch(java.io.IOException e){throw new IllegalArgumentException("Project root unavailable");}}
  static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
  private String encode(Checkpoint state){try{String encoded=json.writeValueAsString(state);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)throw new IllegalArgumentException("Child checkpoint capacity reached");return encoded;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
  private Checkpoint decode(String value){try{if(value.length()>262144)throw new IllegalStateException("Child checkpoint capacity reached");var state=json.readValue(value,Checkpoint.class);if(state.version()!=1 || state.revision()<0 || state.consumedCalls()<0 || state.consumedTokens()<0 || state.operations().size()>64 || state.result()!=null && !hash(state.result()).equals(state.resultHash()))throw new IllegalStateException("Invalid child checkpoint evidence");return state;}catch(java.io.IOException e){throw new IllegalStateException("Child checkpoint unavailable",e);}}
}
