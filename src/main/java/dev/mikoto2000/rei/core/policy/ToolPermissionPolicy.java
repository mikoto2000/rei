package dev.mikoto2000.rei.core.policy;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Capability grants come from administrator configuration, never model-provided arguments. */
@Component
@EnableConfigurationProperties(ToolPermissionProperties.class)
public class ToolPermissionPolicy {
  private static final Set<String> READ=Set.of("readFile","readMultiFile","grepMultiQuery","readPdfFile",
      "searchAndRead","today","now","findFile","listFile","repositoryMap","changeTestImpact","getShellProcessStatus","waitForShellProcess","listScheduledActions","listCodexReviews","getCodexReview");
  private static final Set<String> NETWORK_READ=Set.of("webSearch","webSearchAndRead","fetchUrlContent");
  private static final Set<String> LOCAL_WRITE=Set.of("applyTextDiff","writeMultiFile","createDirectory","copyFile","scheduleAfter","scheduleAt","scheduleInterval");
  private final ToolPermissionProperties properties;
  public ToolPermissionPolicy(ToolPermissionProperties properties) {this.properties=properties;}
  public Set<ActionCapability> capabilities(String tool) {
    var configured=properties.capabilities().get(tool);
    if(configured!=null) return configured;
    if(READ.contains(tool)) return Set.of(ActionCapability.READ);
    if(NETWORK_READ.contains(tool)) return Set.of(ActionCapability.NETWORK_READ);
    if(LOCAL_WRITE.contains(tool)) return Set.of(ActionCapability.LOCAL_WRITE);
    if("searchKnowledge".equals(tool))return Set.of(ActionCapability.READ,ActionCapability.NETWORK_READ);
    if(Set.of("deleteFile","moveFile","killShellProcess").contains(tool))
      return Set.of(ActionCapability.LOCAL_WRITE,ActionCapability.DESTRUCTIVE);
    // Arbitrary commands and unknown/MCP callbacks may exercise any capability.
    return Set.copyOf(EnumSet.allOf(ActionCapability.class));
  }
  public PermissionDecision evaluate(String tool) {
    if(!properties.enabled()) return PermissionDecision.AUTO_APPROVE;
    var required=capabilities(tool);
    if(required.stream().anyMatch(properties.denied()::contains)) return PermissionDecision.DENY;
    return properties.autoApprove().containsAll(required)?PermissionDecision.AUTO_APPROVE:PermissionDecision.REQUIRE_APPROVAL;
  }
}
