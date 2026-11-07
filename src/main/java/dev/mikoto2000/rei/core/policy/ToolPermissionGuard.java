package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ErrorInformation;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/**
 * ツール利用の権限制御に必要なツール権限利用条件の確認を扱う。
 *
 * <p>Reports a refusal through existing durable Tool events before invoking the callback.
 */
@Component
public class ToolPermissionGuard {
  // MCP has project-scoped snapshot reads only, not personal memory, raw process logs or arbitrary
  // PDF paths.
  /** MCP読み取りツール一覧。 */
  private static final java.util.Set<String> MCP_READ_TOOLS =
      java.util.Set.of(
          "readMultiFile",
          "grepMultiQuery",
          "searchAndRead",
          "searchFilePage",
          "findFile",
          "listFile",
          "repositoryMap",
          "changeTestImpact",
          "now",
          "webSearch",
          "webSearchAndRead");

  /** 承認一覧。 */
  private BiFunction<AgentRunContext, ApprovalInvocation, ApprovalAttempt> authorizeApproval;

  /** ツール利用の権限制御で使用する承認一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setApprovals(ToolApprovalRepository approvals) {
    this.authorizeApproval =
        approvals == null
            ? null
            : (owner, invocation) -> {
              if (approvals.consume(invocation.tool(), invocation.input(), owner))
                return new ApprovalAttempt(true, null);
              return new ApprovalAttempt(
                  false, approvals.request(invocation.tool(), invocation.input(), owner).id());
            };
  }

  /** 判断基準。 */
  private final java.util.function.BiConsumer<String, String> validatePolicyArguments;
  private final java.util.function.BiFunction<String, String, PermissionDecision> evaluatePolicy;
  private final java.util.function.Function<String, PermissionDecision> evaluateDefaultPolicy;
  private final java.util.function.Function<String, java.util.Set<ActionCapability>> capabilities;
  private final java.util.function.BiPredicate<String, String> readOnly;
  private final java.util.function.BooleanSupplier fullAuto;

  /** イベント一覧。 */
  private final AgentEventFactory events;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 指定された値や利用先を使って、ToolPermissionGuardを初期化する。 */
  public ToolPermissionGuard(
      ToolPermissionPolicy policy, AgentEventFactory events, AgentEventPublisher publisher) {
    this.validatePolicyArguments = (tool, input) -> policy.validateArguments(tool, input);
    this.evaluatePolicy = (tool, input) -> policy.evaluate(tool, input);
    this.evaluateDefaultPolicy = tool -> policy.evaluate(tool);
    this.capabilities = tool -> policy.capabilities(tool);
    this.readOnly = (tool, input) -> policy.readOnly(tool, input);
    this.fullAuto = () -> policy.fullAuto();
    this.events = events;
    this.publisher = publisher;
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public void check(String tool, AgentRunContext owner) {
    check(tool, "", owner);
  }

  /** 利用条件に合わない指定を受け付けないよう、引数を検証する。 */
  public void validateArguments(String tool, String input) {
    validatePolicyArguments.accept(tool, input);
  }

  /** 利用条件に合わない指定を受け付けないよう、引数を検証する。 */
  public void validateArguments(String tool, String input, AgentRunContext owner) {
    try {
      validatePolicyArguments.accept(tool, input);
    } catch (InvalidToolArgumentsException invalid) {
      // A model selection error must not turn an actual capability/ownership DENY into retries.
      if (invalid.kind() == InvalidToolArgumentsException.Kind.MODEL_SELECTION
          && (restricted(tool, input, owner)
              || evaluatePolicy.apply(tool, input) == PermissionDecision.DENY))
        check(tool, input, owner);
      throw invalid;
    }
  }

  /**
   * ツール利用の権限制御において、automatically承認済み読み取りに該当するか判定する。
   *
   * <p>A one-use approval is never a reusable grant for background read retries.
   */
  public boolean automaticallyApprovedRead(String tool) {
    return evaluateDefaultPolicy.apply(tool) == PermissionDecision.AUTO_APPROVE
        && capabilities.apply(tool).stream()
            .allMatch(c -> c == ActionCapability.READ || c == ActionCapability.NETWORK_READ);
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public void check(String tool, String input, AgentRunContext owner) {
    check(
        tool,
        input,
        owner,
        owner != null && !owner.conversationId().startsWith("subagent:") ? owner : null);
  }

  /**
   * 後続の操作を進められるか判断するため、許可済み計画判断基準を確認する。
   *
   * <p>Recheck an already approved exact plan between its sequential sends; never consume another
   * grant.
   */
  public void checkAuthorizedPlanPolicy(String tool, String input, AgentRunContext owner) {
    if (restricted(tool, input, owner) || evaluatePolicy.apply(tool, input) == PermissionDecision.DENY)
      check(tool, input, owner);
    validatePolicyArguments.accept(tool, input);
  }

  /**
   * 後続の操作を進められるか判断するため、委譲済みを確認する。
   *
   * <p>Only the runner passes its captured parent; no model-supplied identity or wildcard grant.
   */
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
            && capabilities.apply(tool).stream()
                .allMatch(
                    capability ->
                        capability == ActionCapability.READ
                            || capability == ActionCapability.NETWORK_READ);
    check(tool, input, child, matching ? parent : null);
  }

  /** ツール利用の権限制御において、restrictedに該当するか判定する。 */
  private boolean restricted(String tool, String input, AgentRunContext owner) {
    boolean restricted =
        owner != null
            && (owner.mode() == AgentRunContext.Mode.CONVERSATION
                || owner.mode() == AgentRunContext.Mode.READ_ONLY && !readOnly.test(tool, input));
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

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  private void check(
      String tool, String input, AgentRunContext owner, AgentRunContext approvalOwner) {
    var decision =
        restricted(tool, input, owner) ? PermissionDecision.DENY : evaluatePolicy.apply(tool, input);
    if (decision != PermissionDecision.DENY) validatePolicyArguments.accept(tool, input);
    // Speech requires exact-input approval unless startup explicitly opted into common full auto.
    if (decision == PermissionDecision.AUTO_APPROVE
        && !fullAuto.getAsBoolean()
        && owner != null
        && owner.voiceInput()
        && !readOnly.test(tool, input)) decision = PermissionDecision.REQUIRE_APPROVAL;
    if (decision == PermissionDecision.AUTO_APPROVE) return;
    String request = null;
    if (decision == PermissionDecision.REQUIRE_APPROVAL
        && authorizeApproval != null
        && approvalOwner != null
        && approvalOwner.projectId() != null) {
      var approval = authorizeApproval.apply(approvalOwner, new ApprovalInvocation(tool, input));
      if (approval.granted()) return;
      request = approval.requestId();
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

  private record ApprovalInvocation(String tool, String input) {}

  private record ApprovalAttempt(boolean granted, String requestId) {}
}
