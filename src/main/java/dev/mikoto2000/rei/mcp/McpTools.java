package dev.mikoto2000.rei.mcp;
import java.util.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.conversation.ConversationAdmissionStore;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.workcontext.*;
import dev.mikoto2000.rei.event.CredentialRedactor;
import io.modelcontextprotocol.spec.McpSchema;

/** Fixed-principal application boundary. All caller identities are validated before service access. */
public final class McpTools {
  public static final List<String> NAMES=List.of("rei_projects_list","rei_context_get","rei_task_submit","rei_task_status","rei_task_result","rei_task_cancel");
  private final ProjectRegistry projects;
  private final Set<String> allowed;
  private final WorkContextService contexts;
  private final ChatSubmitService chats;
  private final RunService runs;
  private final McpRequestStore store;
  private final Clock clock;
  private final boolean concurrent;
  private final Duration receiptRetention;
  private final McpCursor cursors;
  private final dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals;
  public McpTools(ProjectRegistry projects,Set<String> allowed,WorkContextService contexts,ChatSubmitService chats,RunService runs,
      McpRequestStore store,Clock clock,boolean concurrent,Duration receiptRetention,McpCursor cursors,
      dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals) {
    this.projects=projects;this.allowed=Set.copyOf(allowed);this.contexts=contexts;this.chats=chats;this.runs=runs;
    this.store=store;this.clock=clock;this.concurrent=concurrent;this.receiptRetention=receiptRetention;this.cursors=cursors;this.approvals=approvals;
  }
  public McpSchema.CallToolResult call(String name,Map<String,Object> args) {
    String correlation=UUID.randomUUID().toString();Map<String,Object> output;boolean error=false;
    try {
      if(args==null)args=Map.of();
      validate(name,args);
      output=switch(name) {
        case "rei_projects_list" -> projects();
        case "rei_context_get" -> context(args);
        case "rei_task_submit" -> submit(args);
        case "rei_task_status" -> status(args);
        case "rei_task_result" -> result(args);
        case "rei_task_cancel" -> cancel(args);
        default -> throw new McpError("INVALID_ARGUMENT");
      };
    }catch(RuntimeException failure) {
      String code=failure instanceof McpError e?e.code():failure instanceof IdempotencyConflictException?"REQUEST_CONFLICT"
          :failure instanceof IdempotencyExpiredException?"REQUEST_EXPIRED":failure instanceof SessionBusyException?"SESSION_BUSY"
          :failure instanceof java.util.concurrent.RejectedExecutionException?"CAPACITY_EXCEEDED"
          :failure instanceof ResourceNotFoundException||failure instanceof RunNotFoundException?"NOT_FOUND"
          :failure instanceof IllegalArgumentException?"INVALID_ARGUMENT":"INTERNAL_ERROR";
      output=Map.of("schema_version",1,"error",Map.of("code",code,"message",message(code),"retryable",Set.of("SESSION_BUSY","CAPACITY_EXCEEDED","RESULT_NOT_READY").contains(code),"correlation_id",correlation));error=true;
    }
    try {store.audit(name,error?((Map<?,?>)output.get("error")).get("code").toString():Boolean.TRUE.equals(output.get("replayed"))?"REPLAYED":"SUCCESS",safeId(args,"project_id"),safeId(args,"run_id"),args!=null&&args.get("request_id") instanceof String s?ConversationAdmissionStore.hash(s):null,correlation);}
    catch(RuntimeException unavailable){output=Map.of("schema_version",1,"error",Map.of("code","INTERNAL_ERROR","message","MCP audit storage unavailable","retryable",false,"correlation_id",correlation));error=true;}
    try {return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(StorageDatabase.JSON.writeValueAsString(output))),error,output,null);}
    catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}
  }
  private static String message(String code) {return switch(code){case "REQUEST_CONFLICT"->"同じrequest_idが異なる内容で使用されています";case "NOT_FOUND","PERMISSION_DENIED"->"対象にアクセスできません";case "INTERNAL_ERROR"->"MCP処理に失敗しました";default->code;};}
  private static String safeId(Map<String,Object> a,String key){return a!=null&&a.get(key) instanceof String s&&s.matches("[A-Za-z0-9._:-]{1,200}")?s:null;}
  private void project(String id){if(!allowed.contains(id)||projects.resolveById(id).isEmpty())throw new McpError("NOT_FOUND");}
  private static String text(Map<String,Object> a,String key,boolean required,int bytes) {
    Object raw=a.get(key);if(raw==null&&!required)return null;
    if(!(raw instanceof String s)||s.isBlank()||s.getBytes(StandardCharsets.UTF_8).length>bytes)throw new McpError("INVALID_ARGUMENT");return s;
  }
  private static List<String> list(Map<String,Object> a,String key) {
    if(!a.containsKey(key))return List.of();if(!(a.get(key) instanceof List<?> values)||values.size()>16)throw new McpError("INVALID_ARGUMENT");
    var result=new ArrayList<String>();for(Object v:values){if(!(v instanceof String s)||s.isBlank()||s.getBytes(StandardCharsets.UTF_8).length>2048)throw new McpError("INVALID_ARGUMENT");result.add(s);}return List.copyOf(result);
  }
  private static long integer(Map<String,Object> a,String key,long fallback,long min,long max) {
    if(!a.containsKey(key))return fallback;Object value=a.get(key);
    if(!(value instanceof Number n)||n.doubleValue()!=n.longValue()||n.longValue()<min||n.longValue()>max)throw new McpError("INVALID_ARGUMENT");return n.longValue();
  }
  private static void validate(String name,Map<String,Object> args) {
    Set<String> keys=switch(name){case "rei_projects_list"->Set.of();case "rei_context_get"->Set.of("project_id","revision");case "rei_task_submit"->Set.of("project_id","instruction","request_id","session_id","constraints","acceptance_criteria");case "rei_task_status"->Set.of("project_id","run_id","request_id");case "rei_task_result"->Set.of("project_id","run_id","cursor","max_chars");case "rei_task_cancel"->Set.of("project_id","run_id");default->throw new McpError("INVALID_ARGUMENT");};
    if(!keys.containsAll(args.keySet()))throw new McpError("INVALID_ARGUMENT");
    try {if(StorageDatabase.JSON.writeValueAsBytes(args).length>65536)throw new McpError("INVALID_ARGUMENT");}catch(java.io.IOException e){throw new McpError("INVALID_ARGUMENT");}
  }
  private Map<String,Object> projects() {
    return Map.of("schema_version",1,"projects",projects.list().stream().filter(p->allowed.contains(p.id())).limit(100)
        .map(p->Map.of("project_id",p.id(),"name",CredentialRedactor.redact(p.name()),"capabilities",List.of("READ_CONTEXT","SUBMIT_READ_ONLY","READ_OWN_RUN","CANCEL_OWN_RUN"))).toList());
  }
  private Map<String,Object> context(Map<String,Object> a) {
    String p=text(a,"project_id",true,200);project(p);
    var value=a.containsKey("revision")?contexts.revision(p,integer(a,"revision",0,1,Long.MAX_VALUE)):contexts.current(p).orElseThrow(()->new McpError("NOT_FOUND"));
    var items=value.items().stream().limit(100).map(i->Map.of("id",i.id(),"kind",i.kind().name(),"text",clip(CredentialRedactor.redact(i.text()),2000),"status",i.status().name())).toList();
    return Map.of("project_id",p,"revision",value.revision(),"updated_at",value.updatedAt().toString(),"summary",items.stream().filter(i->Set.of("PURPOSE","CURRENT_WORK").contains(i.get("kind"))).toList(),"decisions",items.stream().filter(i->i.get("kind").equals("DECISION")).toList(),"pending_items",items.stream().filter(i->Set.of("PENDING","BLOCKER","NEXT_ACTION").contains(i.get("kind"))).toList(),"evidence",value.items().stream().flatMap(i->i.evidence().stream()).limit(32).map(e->Map.of("id",e.id(),"origin",e.origin().name(),"text",clip(CredentialRedactor.redact(e.text()),1000))).toList(),"source_type","HISTORICAL_REFERENCE_UNTRUSTED","truncated",value.items().size()>100 || value.items().stream().anyMatch(i->CredentialRedactor.redact(i.text()).length()>2000) || value.items().stream().flatMap(i->i.evidence().stream()).count()>32 || value.items().stream().flatMap(i->i.evidence().stream()).anyMatch(e->CredentialRedactor.redact(e.text()).length()>1000));
  }
  private synchronized Map<String,Object> submit(Map<String,Object> a) {
    String p=text(a,"project_id",true,200);project(p);String instruction=text(a,"instruction",true,16384);
    String request=text(a,"request_id",true,512);if(request.length()>128)throw new McpError("INVALID_ARGUMENT");
    String session=text(a,"session_id",false,200);var constraints=list(a,"constraints");var criteria=list(a,"acceptance_criteria");
    String fingerprint;
    try {fingerprint=ConversationAdmissionStore.hash(StorageDatabase.JSON.writeValueAsString(Arrays.asList(1,p,instruction,session,constraints,criteria,"READ_ONLY")));}catch(java.io.IOException e){throw new McpError("INTERNAL_ERROR");}
    String hash=ConversationAdmissionStore.hash(request);var old=store.byRequest(hash);
    if(old.isPresent()) {var r=old.get();if(!r.project().equals(p))throw new McpError("REQUEST_CONFLICT");if(!clock.instant().isBefore(r.expires()))throw new McpError("REQUEST_EXPIRED");if(!r.fingerprint().equals(fingerprint))throw new McpError("REQUEST_CONFLICT");if(r.admissionError()!=null)throw new McpError(r.admissionError());return receipt(r,request,true);}
    if(!concurrent)throw new McpError("FEATURE_DISABLED");
    if(session!=null&&!store.ownsSession(p,session))throw new McpError("NOT_FOUND");
    String prompt=instruction+"\n\nConstraints (READ_ONLY authority is fixed):\n"+String.join("\n",constraints)+"\n\nAcceptance criteria (require verification evidence):\n"+String.join("\n",criteria);
    var accepted=chats.submitMcp(prompt,p,session,new McpAdmission(hash,fingerprint,receiptRetention,criteria.size(),criteria));
    return receipt(store.byRun(accepted.runId()).orElseThrow(),request,false);
  }
  private Map<String,Object> receipt(McpRequestStore.Request r,String request,boolean replay) {
    var output=new LinkedHashMap<String,Object>(Map.of("request_id",request,"run_id",r.run(),"session_id",r.session(),"admission_status","ACCEPTED","run_status",store.status(r).status().name(),"created_at",r.created().toString(),"replayed",replay));
    store.checkpoint(r).ifPresent(c->output.put("task_id",c.taskId()));return output;
  }
  private McpRequestStore.Request owned(Map<String,Object> a,boolean requestAllowed) {
    String p=text(a,"project_id",true,200);project(p);String run=text(a,"run_id",!requestAllowed,200),request=requestAllowed?text(a,"request_id",false,512):null;
    if(requestAllowed&&(run==null)==(request==null))throw new McpError("INVALID_ARGUMENT");
    if(request!=null&&request.length()>128)throw new McpError("INVALID_ARGUMENT");
    var r=(run!=null?store.byRun(run):store.byRequest(ConversationAdmissionStore.hash(request))).orElseThrow(()->new McpError("NOT_FOUND"));
    if(!r.project().equals(p))throw new McpError("NOT_FOUND");return r;
  }
  private Map<String,Object> status(Map<String,Object> a) {
    var r=owned(a,true);if(a.containsKey("request_id")&&!clock.instant().isBefore(r.expires()))throw new McpError("REQUEST_EXPIRED");
    var s=store.status(r);var approval=approvals.list(r.project()).stream().filter(v->v.runId().equals(r.run()))
        .map(v->Map.of("id",v.id(),"status",v.status(),"resume_required",s.status()!=RunStatus.RUNNING&&s.status()!=RunStatus.QUEUED)).toList();
    var out=new LinkedHashMap<String,Object>();out.put("run_id",r.run());out.put("task_id",store.checkpoint(r).map(dev.mikoto2000.rei.checkpoint.PersistentCheckpoint::taskId).orElse(null));out.put("session_id",r.session());out.put("state",s.status().name());
    out.put("waiting_reason",s.status()==RunStatus.WAITING?"EXTERNAL_OR_CHECKPOINT_WAIT":null);out.put("progress",null);out.put("approval",approval);if(!approval.isEmpty())store.audit("approval_wait","SAVED_APPROVAL",r.project(),r.run(),r.hash(),UUID.randomUUID().toString());out.put("checkpoint_ref",store.checkpoint(r).map(c->Map.of("task_id",c.taskId(),"revision",c.revision())).orElse(null));out.put("updated_at",r.updatedAt().toString());out.put("retry_after_seconds",s.status().isTerminal()?0:2);return out;
  }
  @SuppressWarnings("unchecked") private Map<String,Object> result(Map<String,Object> a) {
    var r=owned(a,false);store.snapshot(r);r=store.byRun(r.run()).orElseThrow();
    if(r.resultExpires()!=null&&!clock.instant().isBefore(r.resultExpires()))throw new McpError("RESULT_EXPIRED");
    if(r.state()==null)throw new McpError(store.status(r).status()==RunStatus.UNKNOWN?"RUN_STATE_UNKNOWN":"RESULT_NOT_READY");
    if(r.result()==null)throw new McpError("RESULT_EXPIRED");
    try {
      var saved=StorageDatabase.JSON.readValue(r.result(),Map.class);String content=(String)saved.get("content");
      String cursor=text(a,"cursor",false,2000);int offset=cursor==null?0:cursors.decode(cursor,r.project(),r.run(),ConversationAdmissionStore.hash(r.result()));
      if(offset<0||offset>content.length())throw new McpError("INVALID_ARGUMENT");
      int size=(int)integer(a,"max_chars",8000,1,16000);int end=content.offsetByCodePoints(offset,Math.min(content.codePointCount(offset,content.length()),size));
      if(end<content.length()&&end>offset&&Character.isHighSurrogate(content.charAt(end-1)))end--;
      while(end>offset&&content.substring(offset,end).getBytes(StandardCharsets.UTF_8).length>32000)end--;
      if(end<content.length()&&end>offset&&Character.isHighSurrogate(content.charAt(end-1)))end--;
      var out=new LinkedHashMap<String,Object>(saved);out.put("run_id",r.run());out.put("state",r.state());out.put("content",content.substring(offset,end));
      out.put("truncated",end<content.length());out.put("next_cursor",end<content.length()?cursors.encode(r.project(),r.run(),ConversationAdmissionStore.hash(r.result()),end):null);return out;
    }catch(java.io.IOException e){throw new McpError("INTERNAL_ERROR");}
  }
  private Map<String,Object> cancel(Map<String,Object> a) {
    var r=owned(a,false);var s=store.status(r);if(s.status()==RunStatus.UNKNOWN)throw new McpError("RUN_STATE_UNKNOWN");
    if(s.status().isTerminal())return Map.of("run_id",r.run(),"cancel_request_status","ALREADY_TERMINAL","run_status",s.status().name(),"cleanup_status","UNKNOWN","rollback_status","NOT_GUARANTEED");
    var cancelled=runs.cancel(r.run());
    return Map.of("run_id",r.run(),"cancel_request_status",cancelled.accepted()?"ACCEPTED":"NOT_ACCEPTED","run_status",cancelled.run().status().name(),"cleanup_status","UNKNOWN","rollback_status","NOT_GUARANTEED");
  }
  private static String clip(String value,int max){return value.length()<=max?value:value.substring(0,max);}
}
