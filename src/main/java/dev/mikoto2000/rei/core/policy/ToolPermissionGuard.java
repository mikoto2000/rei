package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ErrorInformation;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reports a refusal through existing durable Tool events before invoking the callback. */
@Component
public class ToolPermissionGuard {
  // MCP has project-scoped snapshot reads only, not personal memory, raw process logs or arbitrary
  // PDF paths.
  private static final java.util.Set<String> MCP_READ_TOOLS =
      java.util.Set.of(
          "readMultiFile",
          "grepMultiQuery",
          "searchAndRead",
          "findFile",
          "listFile",
          "repositoryMap",
          "changeTestImpact",
          "now",
          "webSearch",
          "webSearchAndRead");
  private ToolApprovalRepository approvals;

  @org.springframework.beans.factory.annotation.Autowired
  public void setApprovals(ToolApprovalRepository approvals) {
    this.approvals = approvals;
  }

  private final ToolPermissionPolicy policy;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;

  public ToolPermissionGuard(
      ToolPermissionPolicy policy, AgentEventFactory events, AgentEventPublisher publisher) {
    this.policy = policy;
    this.events = events;
    this.publisher = publisher;
  }

  public void check(String tool, AgentRunContext owner) {
    check(tool, "", owner);
  }

  public void validateArguments(String tool, String input) {
    policy.validateArguments(tool, input);
  }

  public void validateArguments(String tool, String input, AgentRunContext owner) {
    try {
      policy.validateArguments(tool, input);
    } catch (InvalidToolArgumentsException invalid) {
      // A model selection error must not turn an actual capability/ownership DENY into retries.
      if (invalid.kind() == InvalidToolArgumentsException.Kind.MODEL_SELECTION
          && (restricted(tool, input, owner)
              || policy.evaluate(tool, input) == PermissionDecision.DENY))
        check(tool, input, owner);
      throw invalid;
    }
  }

  /** A one-use approval is never a reusable grant for background read retries. */
  public boolean automaticallyApprovedRead(String tool) {
    return policy.evaluate(tool) == PermissionDecision.AUTO_APPROVE
        && policy.capabilities(tool).stream()
            .allMatch(c -> c == ActionCapability.READ || c == ActionCapability.NETWORK_READ);
  }

  public void check(String tool, String input, AgentRunContext owner) {
    check(
        tool,
        input,
        owner,
        owner != null && !owner.conversationId().startsWith("subagent:") ? owner : null);
  }

  /**
   * Recheck an already approved exact plan between its sequential sends; never consume another
   * grant.
   */
  public void checkAuthorizedPlanPolicy(String tool, String input, AgentRunContext owner) {
    if (restricted(tool, input, owner) || policy.evaluate(tool, input) == PermissionDecision.DENY)
      check(tool, input, owner);
    policy.validateArguments(tool, input);
  }

  /** Only the runner passes its captured parent; no model-supplied identity or wildcard grant. */
  public void checkDelegated(
      String tool, String input, AgentRunContext child, AgentRunContext parent) {
    boolean matching =
        child != null
            && parent != null
            && child.conversationId().startsWith("subagent:")
            && !parent.conversationId().startsWith("subagent:")
            && parent.projectId() != null
            && parent.projectId().equals(child.projectId())
            && parent.projectRoot().equals(child.projectRoot())
            && parent.requestSource() == child.requestSource()
            && parent.voiceInput() == child.voiceInput()
            && policy.capabilities(tool).stream()
                .allMatch(
                    capability ->
                        capability == ActionCapability.READ
                            || capability == ActionCapability.NETWORK_READ);
    check(tool, input, child, matching ? parent : null);
  }

  private boolean restricted(String tool, String input, AgentRunContext owner) {
    boolean restricted =
        owner != null
            && (owner.mode() == AgentRunContext.Mode.CONVERSATION
                || owner.mode() == AgentRunContext.Mode.READ_ONLY && !policy.readOnly(tool, input));
    restricted |=
        owner != null
            && owner.conversationId().startsWith("subagent:")
            && dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.supports(tool);
    restricted |=
        owner != null
            && owner.requestSource() == AgentRunContext.RequestSource.MCP
            && (owner.mode() != AgentRunContext.Mode.READ_ONLY || !MCP_READ_TOOLS.contains(tool));
    return restricted;
  }

  private void check(
      String tool, String input, AgentRunContext owner, AgentRunContext approvalOwner) {
    var decision =
        restricted(tool, input, owner) ? PermissionDecision.DENY : policy.evaluate(tool, input);
    if (decision != PermissionDecision.DENY) policy.validateArguments(tool, input);
    // Speech requires exact-input approval unless startup explicitly opted into common full auto.
    if (decision == PermissionDecision.AUTO_APPROVE
        && !policy.fullAuto()
        && owner != null
        && owner.voiceInput()
        && !policy.readOnly(tool, input)) decision = PermissionDecision.REQUIRE_APPROVAL;
    if (decision == PermissionDecision.AUTO_APPROVE) return;
    String request = null;
    if (decision == PermissionDecision.REQUIRE_APPROVAL
        && approvals != null
        && approvalOwner != null
        && approvalOwner.projectId() != null) {
      if (approvals.consume(tool, input, approvalOwner)) return;
      request = approvals.request(tool, input, approvalOwner).id();
    }
    var error = new ToolPermissionException(tool, decision);
    publisher.publishBoundary(
        events
            .toolFailed(
                "permission-" + UUID.randomUUID(),
                tool,
                new ErrorInformation(
                    decision == PermissionDecision.DENY ? "PermissionDenied" : "PermissionRequired",
                    request == null
                        ? error.getMessage()
                        : "Approval required: "
                            + request
                            + ". Review with /approval show "
                            + request
                            + "; approve explicitly, then resume or retry the task.",
                    decision.name()))
            .withOwnership(owner));
    throw error.withApprovalId(request);
  }
}
