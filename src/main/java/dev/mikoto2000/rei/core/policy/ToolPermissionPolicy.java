package dev.mikoto2000.rei.core.policy;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Capability grants come from administrator configuration, never model-provided arguments. */
@Component
@EnableConfigurationProperties(ToolPermissionProperties.class)
public class ToolPermissionPolicy {
  private static final Set<String> READ=Set.of("readFile","readMultiFile","grepMultiQuery","readPdfFile",
      "searchAndRead","today","now","findFile","listFile","repositoryMap","changeTestImpact","diagnoseTestReport","diagnoseTestReports","readTextChangeSetBase","inspectTextChangeSet","getShellProcessStatus","waitForShellProcess","waitForFile","dependencyStatus","dependencyHistory","checkDependency","waitForDependency","listScheduledActions","listCodexReviews","getCodexReview");
  private static final Set<String> NETWORK_READ=Set.of("webSearch","webSearchAndRead","fetchUrlContent","checkHttpDependency","waitForHttpDependency");
  private static final Set<String> LOCAL_WRITE=Set.of("applyTextDiff","writeMultiFile","proposeTextChangeSet","applyTextChangeSet","discardTextChangeSet","createDirectory","copyFile","scheduleAfter","scheduleAt","scheduleInterval","scheduleCron","scheduleOnEvent","registerDependency","cancelDependency");
  private final ToolPermissionProperties properties;
  public ToolPermissionPolicy(ToolPermissionProperties properties) {this.properties=properties;}
  public boolean enforced(){return properties.enabled();}
  public Set<ActionCapability> capabilities(String tool) {
    var configured=properties.capabilities().get(tool);
    if(configured!=null) return configured;
    return intrinsicCapabilities(tool);
  }
  /** Parallel authority uses known intrinsic behavior, never an administrator relabeling of arbitrary commands. */
  public static Set<ActionCapability> intrinsicCapabilities(String tool) {
    if("inspectDiagnosedRepair".equals(tool))return Set.of(ActionCapability.READ);
    if("proposeDiagnosedRepair".equals(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if(Set.of("listDurableSubAgents","getDurableSubAgent","getSubAgentGraph","compareSubAgentAnswers").contains(tool))return Set.of(ActionCapability.READ);
    if("reconcileSubAgent".equals(tool))return Set.of(ActionCapability.LOCAL_WRITE);
    if(Set.of("getExternalImplementation","inspectExternalImplementation").contains(tool))return Set.of(ActionCapability.READ);
    // Isolated tests execute administrator-selected project code and retain arbitrary-command authority.
    if("requestCodexImplementation".equals(tool))return Set.copyOf(EnumSet.allOf(ActionCapability.class));
    if("mergeExternalImplementation".equals(tool))return Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.EXECUTE);
    if(Set.of("listClaudeCodeReviews","getClaudeCodeReview").contains(tool))return Set.of(ActionCapability.READ);
    if(READ.contains(tool)) return Set.of(ActionCapability.READ);
    if(NETWORK_READ.contains(tool)) return Set.of(ActionCapability.NETWORK_READ);
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
    if(!properties.enabled()) return PermissionDecision.AUTO_APPROVE;
    var required=capabilities(tool);
    if(required.stream().anyMatch(properties.denied()::contains)) return PermissionDecision.DENY;
    return properties.autoApprove().containsAll(required)?PermissionDecision.AUTO_APPROVE:PermissionDecision.REQUIRE_APPROVAL;
  }
}
