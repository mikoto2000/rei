package dev.mikoto2000.rei.core.policy;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Capability grants come from administrator configuration, never model-provided arguments. */
@Component
@EnableConfigurationProperties(ToolPermissionProperties.class)
public class ToolPermissionPolicy {
  private static final Set<String> CONSERVATIVE_TOOLS=Set.of("runCommand","executeExternalProgram",
      "selfReviewPatch","selfRepairPatch","reviewPatchRequirements","applyDiagnosedRepair",
      "requestCodexImplementation","requestClaudeCodeImplementation","computerUse","checkpointResume");
  private static final Set<String> READ=Set.of("readFile","readMultiFile","readSkillResource","readRawToolResult","grepMultiQuery","readPdfFile",
      "searchAndRead","today","now","findFile","listFile","repositoryMap","changeTestImpact","diagnoseTestReport","diagnoseTestReports","readTextChangeSetBase","inspectTextChangeSet","getShellProcessStatus","waitForShellProcess","waitForFile","dependencyStatus","dependencyHistory","checkDependency","waitForDependency","listScheduledActions","listCodexReviews","getCodexReview");
  private static final Set<String> NETWORK_READ=Set.of("webSearch","webSearchAndRead","fetchUrlContent","checkHttpDependency","waitForHttpDependency");
  private static final Set<String> LOCAL_WRITE=Set.of("applyTextDiff","writeMultiFile","proposeTextChangeSet","applyTextChangeSet","discardTextChangeSet","reconcileTextChangeSet","createDirectory","copyFile","scheduleAfter","scheduleAt","scheduleInterval","scheduleCron","scheduleOnEvent","cancelScheduledAction","registerDependency","cancelDependency");
  private final ToolPermissionProperties properties;
  private dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver delegation;
  @org.springframework.beans.factory.annotation.Autowired
  void setDelegationResolver(dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver resolver) {this.delegation=resolver;}
  public void validateDelegation(String tool,String input) {
    try {
      dev.mikoto2000.rei.subagent.DelegationArguments.validate(tool,input);
      if(delegation!=null)delegation.validate(tool,input);
    }catch(IllegalArgumentException invalid){throw new InvalidToolArgumentsException(invalid.getMessage());}
    catch(dev.mikoto2000.rei.chatgpt.ChatGptException denied) {
      if(denied.permissionReason()==dev.mikoto2000.rei.chatgpt.ChatGptException.PermissionReason.ACCOUNT_REQUIRED
          ||denied.permissionReason()==dev.mikoto2000.rei.chatgpt.ChatGptException.PermissionReason.MODEL_REQUIRED)
        throw new InvalidToolArgumentsException(denied.getMessage());
      throw denied;
    }
  }
  public ToolPermissionPolicy(ToolPermissionProperties properties) {this.properties=properties;}
  /** Stable administrator policy identity for durable authorization audit. */
  public String authorityFingerprint(){return dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256((properties.enabled()+"|"+new TreeSet<>(properties.autoApprove())+"|"+new TreeSet<>(properties.denied())+"|"+new TreeMap<>(properties.capabilities()).entrySet().stream().map(e->e.getKey()+"="+new TreeSet<>(e.getValue())).toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  public boolean enforced(){return properties.enabled();}
  public Set<ActionCapability> capabilities(String tool,String input) {
    if(dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.supports(tool)) {
      var required=EnumSet.noneOf(ActionCapability.class);
      required.addAll(delegation==null?EnumSet.allOf(ActionCapability.class):delegation.resolve(tool,input,this::capabilities));
      var configured=properties.capabilities().get(tool);
      if(configured!=null)required.addAll(configured);
      return Set.copyOf(required);
    }
    return capabilities(tool);
  }
  public boolean readOnly(String tool,String input) {
    var required=dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.supports(tool)
        ?capabilities(tool,input):intrinsicCapabilities(tool);
    return required.stream().allMatch(c->c==ActionCapability.READ||c==ActionCapability.NETWORK_READ);
  }
  /** Explicit conservative classifications are distinct from unknown third-party callbacks. */
  public static boolean classified(String tool) {
    return CONSERVATIVE_TOOLS.contains(tool)
        || dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.supports(tool)
        || !intrinsicCapabilities(tool).equals(Set.copyOf(EnumSet.allOf(ActionCapability.class)));
  }
  public Set<ActionCapability> capabilities(String tool) {
    if(Set.of("askExternalAgent","requestDotResearch").contains(tool))return intrinsicCapabilities(tool);
    var configured=properties.capabilities().get(tool);
    if(configured!=null) return configured;
    return intrinsicCapabilities(tool);
  }
  /** Parallel authority uses known intrinsic behavior, never an administrator relabeling of arbitrary commands. */
  public static Set<ActionCapability> intrinsicCapabilities(String tool) {
    if(CONSERVATIVE_TOOLS.contains(tool))return Set.copyOf(EnumSet.allOf(ActionCapability.class));
    if(Set.of("activityWorkInferences","activitySummary","activityTimeline","activityBetween",
        "checkpointList","checkpointShow","checkpointInspect","searchConversationHistory","getConversationHistory",
        "readBinaryFile","feedList","reminderList","getLastSummary","taskList","getCurrentTime","countCharacters",
        "vectorDocumentList","workContextGet","workContextHistory","workContextRevision","searchPaperLibrary").contains(tool))return Set.of(ActionCapability.READ);
    if(Set.of("createDirectories","writeBinaryFile","updateTaskState","checkpointSave","checkpointAbandon",
        "checkpointAnnotate","checkpointPlan","checkpointConfirm","feedAdd","reminderCreate","taskCreate",
        "taskUpdate","taskComplete","taskUpdateDeadline","workContextUpdate","workContextEdit").contains(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if(Set.of("taskDelete","feedDelete").contains(tool))return Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.DESTRUCTIVE);
    if(Set.of("googleCalendarListEvents","googleCalendarListEventsForDate").contains(tool))return Set.of(ActionCapability.NETWORK_READ);
    if(Set.of("blueskyPost","googleCalendarCreateEvent").contains(tool))return Set.of(ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if("soundNotify".equals(tool))return Set.of(ActionCapability.EXECUTE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if(Set.of("dailyBriefing","vectorDocumentSearch").contains(tool))return Set.of(ActionCapability.READ,ActionCapability.NETWORK_READ);
    if(Set.of("feedUpdate","feedSummarizeBriefing","feedSummarizeItem","searchPapers","getPaper",
        "getPaperContent","getPaperSection","summarizePaper","translatePaper","getPaperTranslationChunk").contains(tool))return Set.of(ActionCapability.READ,ActionCapability.NETWORK_READ,ActionCapability.LOCAL_WRITE);
    if("vectorDocumentAdd".equals(tool))return Set.of(ActionCapability.READ,ActionCapability.LOCAL_WRITE,ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if(Set.of("vectorDocumentDeleteByDocId","vectorDocumentDeleteBySource").contains(tool))return Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT,ActionCapability.DESTRUCTIVE);
    if(Set.of("requestClaudeCodeReview","requestClaudeCodeContinueReview","requestParallelClaudeCodeReviews",
        "requestParallelCodexReviews","requestCodexReview","requestCodexReReview","requestCodexContinueReview").contains(tool))return Set.of(ActionCapability.READ,ActionCapability.EXECUTE,ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if(Set.of("requestClaudeCodeFixProposal","requestCodexFixProposal").contains(tool))return Set.of(ActionCapability.READ,ActionCapability.LOCAL_WRITE,ActionCapability.EXECUTE,ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    // Saved local children can only use the audited read/network-read allowlist. Resuming also updates receipts.
    if(Set.of("resumeSubAgent","resumeSubAgentGraph").contains(tool))return Set.of(ActionCapability.READ,ActionCapability.NETWORK_READ,ActionCapability.LOCAL_WRITE);
    if(Set.of("askExternalAgent","requestDotResearch").contains(tool))return Set.of(ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if("externalAgentStatus".equals(tool))return Set.of(ActionCapability.READ);
    if(Set.of("searchMemoryHistory","episodeGet","episodeSources","episodeProcessingStatus").contains(tool))return Set.of(ActionCapability.READ);
    if(Set.of("doctorConnectivity","doctorInference").contains(tool))return Set.of(ActionCapability.NETWORK_READ);
    if(Set.of("doctorCodexVersion","doctorClaudeVersion").contains(tool))return Set.of(ActionCapability.EXECUTE);
    if("doctorMicrophone".equals(tool))return Set.of(ActionCapability.EXECUTE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if("inspectDocumentRender".equals(tool))return Set.of(ActionCapability.READ);
    if("validateDocumentRender".equals(tool))return Set.of(ActionCapability.READ,ActionCapability.EXECUTE,ActionCapability.LOCAL_WRITE);
    if("inspectTextDocumentChangeSet".equals(tool))return Set.of(ActionCapability.READ);
    if(Set.of("proposeTextDocumentChangeSet","discardTextDocumentChangeSet").contains(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if(Set.of("applyTextDocumentChangeSet","rollbackTextDocumentChangeSet","cleanTextDocumentStaging").contains(tool))return Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.DESTRUCTIVE);
    if(Set.of("inspectDiagnosedRepair","getPatchRequirementReview","inspectPatchRequirementReview","listPatchRequirementReviews","getGoalCompletionDefinition").contains(tool))return Set.of(ActionCapability.READ);
    if("attachGoalCompletionEvidence".equals(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if("proposeDiagnosedRepair".equals(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if(Set.of("listDurableSubAgents","getDurableSubAgent","getSubAgentGraph","compareSubAgentAnswers").contains(tool))return Set.of(ActionCapability.READ);
    if("reconcileSubAgent".equals(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if(Set.of("getCodexImplementationRequest","getExternalImplementation","inspectExternalImplementation").contains(tool))return Set.of(ActionCapability.READ);
    if(Set.of("prepareCodexImplementation","recordCodexAcceptanceEvaluation").contains(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if("mergeExternalImplementation".equals(tool))return Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.EXECUTE);
    if(Set.of("listClaudeCodeReviews","getClaudeCodeReview").contains(tool))return Set.of(ActionCapability.READ);
    if(READ.contains(tool)) return Set.of(ActionCapability.READ);
    if(NETWORK_READ.contains(tool)) return Set.of(ActionCapability.NETWORK_READ);
    if("requestCodexAppServerSubAgent".equals(tool))return Set.of(ActionCapability.EXECUTE,ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if(Set.of("requestChatGptPlanSubAgent","requestChatGptPlanInference").contains(tool))return Set.of(ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if("deliverAttention".equals(tool))return Set.of(ActionCapability.NETWORK_WRITE,ActionCapability.EXTERNAL_SIDE_EFFECT);
    if("registerDependency".equals(tool))return Set.of(ActionCapability.READ,ActionCapability.LOCAL_WRITE);
    if(LOCAL_WRITE.contains(tool)) return Set.of(ActionCapability.LOCAL_WRITE);
    if("searchKnowledge".equals(tool))return Set.of(ActionCapability.READ,ActionCapability.NETWORK_READ);
    if(Set.of("deleteFile","moveFile","killShellProcess").contains(tool))
      return Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.DESTRUCTIVE);
    // Arbitrary commands and unknown/MCP callbacks may exercise any capability.
    return Set.copyOf(EnumSet.allOf(ActionCapability.class));
  }
  public static boolean intrinsicallyReadOnly(String tool) {
    return intrinsicCapabilities(tool).stream().allMatch(c -> c == ActionCapability.READ || c == ActionCapability.NETWORK_READ);
  }
  public PermissionDecision evaluate(String tool) {
    return evaluateCapabilities(capabilities(tool));
  }
  public PermissionDecision evaluate(String tool,String input) {
    return evaluateCapabilities(capabilities(tool,input));
  }
  private PermissionDecision evaluateCapabilities(Set<ActionCapability> required) {
    if(!properties.enabled()) return PermissionDecision.AUTO_APPROVE;
    if(required.stream().anyMatch(properties.denied()::contains)) return PermissionDecision.DENY;
    return properties.autoApprove().containsAll(required)?PermissionDecision.AUTO_APPROVE:PermissionDecision.REQUIRE_APPROVAL;
  }
}
