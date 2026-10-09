package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

/** Specifications and permission are separate: only persisted server data may reach the common engine. */
@Service
public class ImplementationRequestService {
  static final String TOOL="requestCodexImplementation";
  public record Prepared(String requestId,int specificationVersion,String specificationSha256,String status,String summary,
      String target,List<String> allowedPaths,List<String> unresolvedRequirements,boolean approvalRequired,List<String> warnings,String nextAction) {}
  public record Outcome(String requestId,String status,String receiptId,String specificationSha256,String baseCommit,String implementationCommit,
      List<String> changedFiles,String patchSha256,dev.mikoto2000.rei.core.SelfPatchReviewService.Result testResult,
      dev.mikoto2000.rei.core.SelfPatchReviewService.Review staticReview,List<AcceptanceEvaluation> acceptanceResults,
      List<String> warnings,List<String> unmetRequirements,boolean mergePerformed,boolean pushPerformed) {}
  private final ImplementationRequestRepository requests;private final ToolApprovalRepository approvals;private final ToolPermissionPolicy policy;
  private final CodexProperties properties;private final ExternalAgentDelegationService delegation;private final Clock clock;
  private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  private final String instance=UUID.randomUUID().toString();private final Set<String> active=ConcurrentHashMap.newKeySet();
  public ImplementationRequestService(ImplementationRequestRepository requests,ToolApprovalRepository approvals,ToolPermissionPolicy policy,
      CodexProperties properties,ExternalAgentDelegationService delegation,Clock clock) {
    this.requests=requests;this.approvals=approvals;this.policy=policy;this.properties=properties;this.delegation=delegation;this.clock=clock;
  }
  public Prepared prepare(RunExecutionContext run,ImplementationSpecification specification,String previousRequestId) {
    return prepare(run,specification,previousRequestId,null);
  }
  public Prepared prepare(RunExecutionContext run,ImplementationSpecification specification,String previousRequestId,String clarificationRequestId) {
    var owner=owner(run);run.checkActive();
    var clarification=clarificationRequestId==null?null:owned(owner,clarificationRequestId);
    if(clarification!=null) {
      if(!clarification.executionStatus().equals("NEEDS_CLARIFICATION") || clarification.createdAt().plus(Duration.ofMinutes(15)).isBefore(clock.instant()))return refused("Fresh owned clarification required");
      if(previousRequestId!=null&&!Objects.equals(previousRequestId,clarification.previousRequestId()))return refused("Retry origin differs from clarification");
      previousRequestId=clarification.previousRequestId();
    }
    if(!enabled() || !(humanImplementationRequest(run.userRequest()) || clarification!=null&&humanClarification(run.userRequest())))return refused("Actual human Codex implementation request and administrator opt-in required");
    var decision=decision();if(decision==PermissionDecision.DENY)return refused("Existing Policy denies implementation");
    if(previousRequestId!=null) {
      var previous=owned(owner,previousRequestId);
      if(!Set.of("UNKNOWN","EXECUTING","VERIFYING").contains(previous.executionStatus()))throw new IllegalArgumentException("Retry link requires an unknown original request");
      if(active.contains(previousRequestId))throw new IllegalArgumentException("Original request is still executing; inspect it first");
    }
    String slashTarget=run.userRequest().strip().startsWith("/agent ")?ExternalAgentCommandRequest.parse(run.userRequest()).target():null;
    if(clarification!=null&&!clarification.executionEnvelope().isBlank())slashTarget=read(clarification.executionEnvelope(),com.fasterxml.jackson.databind.JsonNode.class).path("slashTarget").textValue();
    String origin=clarification==null?owner.runId()+":human:"+ImplementationSpecificationValidator.hash(run.userRequest()):"draft:"+clarification.requestId()+":confirmed:"+owner.runId()+":"+ImplementationSpecificationValidator.hash(run.userRequest());
    String source=clarification==null?(slashTarget==null?"NATURAL_LANGUAGE":"SLASH_COMMAND"):clarification.sourceType();
    ImplementationSpecificationValidator.Validated validated;
    try{validated=ImplementationSpecificationValidator.validate(owner.projectRoot(),specification);}
    catch(IllegalArgumentException missing){
      String draft=ImplementationSpecificationValidator.serialize(specification);
      if(draft.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)throw new IllegalArgumentException("Draft specification exceeds 32 KiB; reduce input");
      String hash=ImplementationSpecificationValidator.hash(specification);String id=requests.find(owner.projectId(),owner.conversationId(),owner.runId(),hash,previousRequestId).map(ImplementationRequestRepository.Request::requestId).orElse(null);
      if(id==null){id=UUID.randomUUID().toString();var now=clock.instant();requests.save(new ImplementationRequestRepository.Request(id,1,hash,owner.projectId(),realRoot(owner),owner.conversationId(),owner.runId(),origin,
          source,"codex","",previousRequestId,draft,slashTarget==null?"":ImplementationSpecificationValidator.serialize(Map.of("slashTarget",slashTarget)),"NOT_EVALUATED",null,"NEEDS_CLARIFICATION",null,null,now,now));}
      return new Prepared(id,1,null,"NEEDS_CLARIFICATION","Confirm detailed requirements before execution",specification==null?null:specification.target(),List.of(),List.of(missing.getMessage()),false,List.of(),"Ask the user to confirm objective, instructions, existing target, allowedPaths and acceptanceCriteria");
    }
    if(slashTarget!=null && !ExternalAgentRequest.resolveTarget(owner.projectRoot(),slashTarget).equals(ExternalAgentRequest.resolveTarget(owner.projectRoot(),validated.specification().target())))
      return refused("Specification target differs from the actual slash command target");
    String base=base(owner.projectRoot());String envelope=envelope(owner,base,validated.specification());
    String root=realRoot(owner);var existing=requests.find(owner.projectId(),owner.conversationId(),owner.runId(),validated.sha256(),previousRequestId);
    if(existing.isPresent()) {
      var saved=existing.get();if(!saved.executionEnvelope().equals(envelope))throw new IllegalArgumentException("Execution specification changed; start a new human request");
      return prepared(saved);
    }
    String id=UUID.randomUUID().toString();String approvalInput=approvalInput(id,validated.canonical(),envelope);
    if(approvalInput.length()>16384)throw new IllegalArgumentException("Complete approval specification exceeds existing 16384 character limit; reduce scope, never truncate");
    boolean automatic=decision==PermissionDecision.AUTO_APPROVE&&previousRequestId==null;
    var now=clock.instant();
    var request=new ImplementationRequestRepository.Request(id,1,validated.sha256(),owner.projectId(),root,owner.conversationId(),owner.runId(),
        origin,source,"codex",base,previousRequestId,validated.canonical(),envelope,
        decision.name()+":"+policy.authorityFingerprint(),automatic?"policy:"+policy.authorityFingerprint():null,automatic?"AUTHORIZED":"AWAITING_APPROVAL",null,null,now,now);
    requests.save(request);
    if(!automatic) {
      var approval=approvals.request(TOOL,approvalInput,owner);requests.authorization(id,request.policyDecision(),approval.id(),"AWAITING_APPROVAL");
    }
    return prepared(requests.get(id));
  }
  public Outcome execute(RunExecutionContext run,String id,int version,String hash) {
    var owner=owner(run);run.checkActive();var saved=owned(owner,id);
    if(version!=saved.specificationVersion() || !Objects.equals(hash,saved.specificationSha256()))throw new IllegalArgumentException("Saved specification version/hash mismatch");
    if(saved.result()!=null)return evaluated(read(saved.result(),Outcome.class));
    if(Set.of("EXECUTING","VERIFYING","UNKNOWN").contains(saved.executionStatus())) {
      String status=active.contains(id)&&instance.equals(requests.claimOwner(id))?saved.executionStatus():"UNKNOWN";
      if(status.equals("UNKNOWN")){var reconciled=reconcile(run,saved);if(reconciled!=null)return reconciled;}
      return pending(saved,status,"Inspect existing receipt/worktree read-only; never automatically re-execute an unknown attempt");
    }
    if(!Set.of("AUTHORIZED","AWAITING_APPROVAL").contains(saved.executionStatus()))return pending(saved,saved.executionStatus(),"Request is not executable");
    if(saved.authorizationId()!=null&&!saved.authorizationId().startsWith("policy:")&&approvals.get(owner.projectId(),saved.authorizationId()).status().equals("DENIED")) {
      requests.authorization(id,saved.policyDecision(),saved.authorizationId(),"REJECTED");return pending(saved,"REJECTED","Human approval was denied; a later automatic Policy cannot override that refusal");
    }
    if(!enabled())return pending(saved,"REJECTED","Administrator opt-in or bounded test recipe is unavailable");
    var validated=ImplementationSpecificationValidator.validate(owner.projectRoot(),read(saved.canonicalSpecification(),ImplementationSpecification.class));
    if(!validated.canonical().equals(saved.canonicalSpecification()) || !validated.sha256().equals(saved.specificationSha256()))throw new IllegalArgumentException("Persisted canonical specification changed");
    String current=envelope(owner,base(owner.projectRoot()),validated.specification());
    if(!current.equals(saved.executionEnvelope()))throw new IllegalArgumentException("Project, baseline, scope or administrator test recipe changed; prepare and authorize a new request");
    var decision=decision();if(decision==PermissionDecision.DENY)return pending(saved,"REJECTED","Current Policy denies implementation");
    boolean explicit=saved.previousRequestId()!=null || decision!=PermissionDecision.AUTO_APPROVE
        || saved.authorizationId()!=null&&!saved.authorizationId().startsWith("policy:");
    String authorization="policy:"+policy.authorityFingerprint();
    if(explicit) {
      String input=approvalInput(id,saved.canonicalSpecification(),saved.executionEnvelope());
      if(!approvals.consume(TOOL,input,owner)) {
        var approval=approvals.request(TOOL,input,owner);
        String state=approval.status().equals("DENIED")?"REJECTED":"AWAITING_APPROVAL";
        requests.authorization(id,decision.name()+":"+policy.authorityFingerprint(),approval.id(),state);
        return pending(requests.get(id),state,"Review /approval show "+approval.id()+"; explicit approval and resume required");
      }
      authorization=saved.authorizationId();
    }
    run.checkActive();requests.authorization(id,decision.name()+":"+policy.authorityFingerprint(),authorization,"AUTHORIZED");
    if(!requests.claim(id,instance))return execute(run,id,version,hash);
    active.add(id);
    try {
      // Baseline and recipe are checked again by the shared engine immediately before launch.
      var frozen=read(saved.executionEnvelope(),com.fasterxml.jackson.databind.JsonNode.class);
      var receipt=delegation.executeSpecification(run,id,validated,saved.baseCommit(),frozen.path("testCommand").textValue(),frozen.path("testTimeoutSeconds").intValue());
      return complete(saved,validated.specification(),receipt);
    }catch(RuntimeException error) {
      // Process/storage uncertainty is never turned into a fresh automatic attempt, including cancellation.
      requests.finish(id,"UNKNOWN",id,null);throw error;
    }finally{active.remove(id);}
  }
  public Outcome get(RunExecutionContext run,String id) {
    var saved=owned(owner(run),id);run.checkActive();
    if(saved.result()!=null)return evaluated(read(saved.result(),Outcome.class));
    String status=Set.of("EXECUTING","VERIFYING").contains(saved.executionStatus())&&!active.contains(id)?"UNKNOWN":saved.executionStatus();
    if(status.equals("UNKNOWN")){var reconciled=reconcile(run,saved);if(reconciled!=null)return reconciled;}
    return pending(saved,status,"Saved state only; use getExternalImplementation for the linked receipt. Never automatically retry UNKNOWN");
  }
  private Outcome reconcile(RunExecutionContext run,ImplementationRequestRepository.Request saved) {
    if(saved.receiptId()==null)return null;
    IsolatedImplementationService.Receipt receipt;
    try{receipt=delegation.implementation(run,saved.receiptId());}catch(IllegalArgumentException unavailable){return null;}
    if(receipt==null||!Set.of("READY_FOR_APPROVAL","FAILED").contains(receipt.status()))return null;
    if(!Objects.equals(saved.baseCommit(),receipt.baseline()))return null;
    return complete(saved,read(saved.canonicalSpecification(),ImplementationSpecification.class),receipt);
  }
  private Outcome complete(ImplementationRequestRepository.Request saved,ImplementationSpecification specification,IsolatedImplementationService.Receipt receipt) {
    var evaluations=AcceptanceEvaluation.unverified(specification,receipt);
    String status=switch(receipt.status()){case "READY_FOR_APPROVAL"->"RESULT_AVAILABLE";case "FAILED"->"FAILED";default->"UNKNOWN";};
    var outcome=new Outcome(saved.requestId(),status,receipt.id(),saved.specificationSha256(),receipt.baseline(),receipt.commitHash(),receipt.changedFiles(),receipt.patchHash(),receipt.verification(),
        receipt.verification()==null?null:receipt.verification().review(),evaluations,List.of(receipt.diagnostic(),"READY_FOR_APPROVAL only describes technical isolation; business acceptance remains independently evaluated","No automatic merge or push"),
        evaluations.stream().filter(e->!e.status().equals("VERIFIED")).map(AcceptanceEvaluation::criterionId).toList(),false,false);
    requests.finish(saved.requestId(),status,receipt.id(),ImplementationSpecificationValidator.serialize(outcome));return outcome;
  }
  /** Semantic assessment is explicitly labelled PARENT_LLM, separate from objective server checks. */
  public Outcome evaluate(RunExecutionContext run,String id,String patch,List<AcceptanceEvaluation> input) {
    var owner=owner(run);run.checkActive();var saved=owned(owner,id);
    if(saved.result()==null)throw new IllegalArgumentException("Saved implementation outcome required");
    var result=read(saved.result(),Outcome.class);
    if(!Objects.equals(result.patchSha256(),patch))throw new IllegalArgumentException("Evaluation patch differs from saved result");
    var receipt=delegation.implementation(run,result.receiptId());
    if(!Objects.equals(receipt.patchHash(),patch))throw new IllegalArgumentException("Receipt patch changed");
    var values=AcceptanceEvaluation.parent(read(saved.canonicalSpecification(),ImplementationSpecification.class),receipt,input);
    requests.saveEvaluations(id,saved.specificationSha256(),receipt.id(),patch,ImplementationSpecificationValidator.serialize(values));
    return withEvaluations(result,values);
  }
  private Outcome evaluated(Outcome result) {
    return requests.evaluations(result.requestId(),result.specificationSha256(),result.receiptId(),result.patchSha256())
        .map(value->withEvaluations(result,List.of(read(value,AcceptanceEvaluation[].class)))).orElse(result);
  }
  private Outcome withEvaluations(Outcome result,List<AcceptanceEvaluation> values) {
    return new Outcome(result.requestId(),result.status(),result.receiptId(),result.specificationSha256(),result.baseCommit(),result.implementationCommit(),result.changedFiles(),result.patchSha256(),result.testResult(),result.staticReview(),values,
        result.warnings(),values.stream().filter(e->!e.status().equals("VERIFIED")).map(AcceptanceEvaluation::criterionId).toList(),false,false);
  }
  private PermissionDecision decision(){var result=policy.evaluate(TOOL);return result==PermissionDecision.AUTO_APPROVE&&!policy.enforced()?PermissionDecision.REQUIRE_APPROVAL:result;}
  private boolean enabled(){return properties.isEnabled()&&properties.isImplementationEnabled()&&properties.getImplementationTestCommand()!=null&&!properties.getImplementationTestCommand().isBlank()&&properties.getImplementationTestCommand().length()<=4096&&properties.getImplementationTestTimeoutSeconds()>=1&&properties.getImplementationTestTimeoutSeconds()<=60;}
  private AgentRunContext owner(RunExecutionContext run) {
    if(run==null || run.runContext()==null)throw new IllegalArgumentException("Current Run required");var owner=run.runContext();
    if(owner.projectId()==null || owner.mode()!=AgentRunContext.Mode.EXCLUSIVE || owner.conversationId().startsWith("subagent:"))throw new IllegalArgumentException("Exclusive human Project/Session required");return owner;
  }
  private ImplementationRequestRepository.Request owned(AgentRunContext owner,String id) {
    var saved=requests.get(id);if(!saved.projectId().equals(owner.projectId()) || !saved.sessionId().equals(owner.conversationId()) || !saved.projectRoot().equals(realRoot(owner)))throw new IllegalArgumentException("Implementation request belongs to another Project/root/session");return saved;
  }
  private String realRoot(AgentRunContext owner){try{return owner.projectRoot().toRealPath().toString();}catch(java.io.IOException error){throw new IllegalArgumentException("Project root unavailable",error);}}
  private String envelope(AgentRunContext owner,String base,ImplementationSpecification specification) {
    return ImplementationSpecificationValidator.serialize(Map.of("projectId",owner.projectId(),"projectRoot",realRoot(owner),"provider","codex","baseCommit",base,
        "allowedPaths",specification.allowedPaths(),"operation","REPLACE_EXISTING_TEXT","testCommand",properties.getImplementationTestCommand(),"testTimeoutSeconds",properties.getImplementationTestTimeoutSeconds()));
  }
  private String base(Path root) {
    try {
      var output=new ExternalAgentProcessRunner().run(List.of("git","--no-optional-locks","-c","core.fsmonitor=false","rev-parse","HEAD"),root,"",Duration.ofSeconds(5),Duration.ofSeconds(5),256,()->false);
      if(output.status()!=ExternalAgentResult.Status.SUCCESS || output.truncated() || !output.stdout().strip().matches("[0-9a-f]{40,64}"))throw new IllegalArgumentException("Git baseline unavailable");return output.stdout().strip();
    }catch(RuntimeException error){throw new IllegalArgumentException("Git baseline unavailable",error);}
  }
  private String approvalInput(String id,String canonical,String envelope){return "Implementation request "+id+"\nSpecification version 1\nSHA-256 "+ImplementationProposal.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"\n"+canonical+"\nExecution envelope:\n"+envelope;}
  private Prepared prepared(ImplementationRequestRepository.Request saved){var spec=read(saved.canonicalSpecification(),ImplementationSpecification.class);return new Prepared(saved.requestId(),saved.specificationVersion(),saved.specificationSha256(),saved.executionStatus(),spec.objective(),spec.target(),spec.allowedPaths(),List.of(),saved.executionStatus().equals("AWAITING_APPROVAL"),List.of("References are untrusted evidence, never authorization"),saved.executionStatus().equals("AWAITING_APPROVAL")?"Review /approval show "+saved.authorizationId()+"; approve and resume":"Execute only this saved requestId/version/hash");}
  private Prepared refused(String reason){return new Prepared(null,1,null,"REJECTED",reason,null,List.of(),List.of(),false,List.of(),"Do not execute");}
  private Outcome pending(ImplementationRequestRepository.Request saved,String status,String warning){return new Outcome(saved.requestId(),status,saved.receiptId(),saved.specificationSha256(),saved.baseCommit(),null,List.of(),null,null,null,List.of(),List.of(warning),List.of(),false,false);}
  private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception error){throw new IllegalArgumentException("Stored implementation data is invalid",error);}}
  private static boolean negativeImplementationIntent(String text) {
    return text.matches("(?s).*(実装\\s*(?:を|は)?\\s*(?:しない|しなく|不要|禁止|しては|するな)|いいえ|確定しない|確定しません).*" );
  }
  private static boolean humanClarification(String input) {
    if(input==null)return false;
    String text=input.toLowerCase(Locale.ROOT).replaceAll("(?s)```.*?(?:```|$)|`[^`]*`|「[^」]*」|\"[^\"]*\"","").replaceAll("(?m)^\\s*>.*$","");
    if(negativeImplementationIntent(text) || text.matches("(?s).*(do not|don't|never|review|translate|explain|翻訳|説明|実装しない|実装不要|実装禁止|という).*"))return false;
    return text.matches("(?s).*(対象|指示|条件|目的|制約|要件|確定|はい|\\byes\\b|\\bconfirm\\b|\\btarget\\b|\\bcriteria\\b).*" );
  }
  static boolean humanImplementationRequest(String input) {
    if(input==null)return false;
    if(input.strip().startsWith("/agent ")){try{var command=ExternalAgentCommandRequest.parse(input);return command.agent().equals("codex")&&command.action().equals("implement");}catch(IllegalArgumentException invalid){return false;}}
    String text=input.toLowerCase(Locale.ROOT).replaceAll("(?s)```.*?(?:```|$)|`[^`]*`|「[^」]*」|\"[^\"]*\"","");
    text=text.replaceAll("(?m)^\\s*>.*$","");
    if(negativeImplementationIntent(text) || text.matches("(?s).*(do not|don't|never|翻訳|という|実装しない|実装不要|実装禁止|説明|example|translate|explain|how to).*"))return false;
    return !text.contains("claude")&&text.matches("(?s).*(実装(?:を)?(?:して|お願い|依頼)|^implement\\b|\\bimplement\\b.{0,80}(please|codex)|(?:please|ask|use|have).{0,80}\\bimplement\\b).*" );
  }
}
