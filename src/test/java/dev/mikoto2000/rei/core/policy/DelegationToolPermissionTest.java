package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver;
import dev.mikoto2000.rei.subagent.SubAgentDefinition;
import dev.mikoto2000.rei.subagent.SubAgentRegistry;
import dev.mikoto2000.rei.subagent.SubAgentResultSchema;
import dev.mikoto2000.rei.subagent.SubAgentToolPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 委譲ツール権限の結果と状態の契約を検証するテスト。 */
class DelegationToolPermissionTest {
  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void missingAccountIsRejectedBeforeRequestingOrConsumingApproval() {
    var repository = mock(ToolApprovalRepository.class);
    var guard = guard();
    guard.setApprovals(repository);
    String input =
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"modelId\":\"gpt-6.1-sol\",\"sourceRefs\":[\"docs/design.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}";
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                guard.check(
                    "delegateTask", input, owner(AgentRunContext.Mode.EXCLUSIVE, "session")));
    assertTrue(error.getMessage().contains("ACCOUNT_REQUIRED"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(repository);
  }

  /** 検証に使う判断基準。 */
  private ToolPermissionPolicy policy;

  /** 検証に使う登録台帳。 */
  private SubAgentRegistry registry;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    registry = mock(SubAgentRegistry.class);
    when(registry.findById("reviewer"))
        .thenReturn(Optional.of(definition("reviewer", List.of("readMultiFile"))));
    when(registry.findById("researcher"))
        .thenReturn(Optional.of(definition("researcher", List.of("webSearch"))));
    when(registry.findById("writer"))
        .thenReturn(Optional.of(definition("writer", List.of("writeMultiFile"))));
    when(registry.findById("judge")).thenReturn(Optional.of(definition("judge", List.of())));
    ObjectProvider<SubAgentRegistry> registries = mock(ObjectProvider.class);
    when(registries.getObject()).thenReturn(registry);
    ObjectProvider<SubAgentToolPolicy> policies = mock(ObjectProvider.class);
    when(policies.getObject())
        .thenReturn(new SubAgentToolPolicy(Set.of("readMultiFile", "webSearch", "writeMultiFile")));
    policy = new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null));
    policy.setDelegationResolver(new DelegationToolPermissionResolver(registries, policies));
  }

  /** {@code definition} の条件を再現するための補助情報を返す。 */
  private SubAgentDefinition definition(String agent, List<String> tools) {
    return new SubAgentDefinition(
        agent,
        agent,
        "test",
        "test",
        tools,
        "model",
        4,
        Duration.ofSeconds(30),
        Path.of("agent.yaml"));
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  private AgentRunContext owner(AgentRunContext.Mode mode, String session) {
    return new AgentRunContext(
        "run", session, Path.of("."), "project", AgentRunContext.RequestSource.WEB, mode);
  }

  /** 検証で使用する利用条件の確認を、指定された条件に合わせて用意する。 */
  private ToolPermissionGuard guard() {
    return new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), event -> {});
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void blankBackendIsLocalForPermissionChecks() {
    String input =
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"  \",\"accountRef\":\"\",\"modelId\":\"\",\"context\":\"\",\"sourceRefs\":[],\"expectedOutput\":\"\"}";
    assertEquals(Set.of(ActionCapability.READ), policy.capabilities("delegateTask", input));
    assertDoesNotThrow(
        () ->
            guard().check("delegateTask", input, owner(AgentRunContext.Mode.EXCLUSIVE, "session")));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void expectedOutputTemplateIsRejectedBeforeApprovalCreationOrConsumption() {
    var original = definition("reviewer", List.of("readMultiFile"));
    var schema =
        SubAgentResultSchema.load(
            Path.of("."), "classpath:/subagents/schemas/reviewer-result.schema.json");
    when(registry.findById("reviewer"))
        .thenReturn(
            Optional.of(
                new SubAgentDefinition(
                    original.id(),
                    original.name(),
                    original.description(),
                    original.systemPrompt(),
                    original.requestedTools(),
                    original.model(),
                    original.maxSteps(),
                    original.timeout(),
                    original.source(),
                    schema)));
    var repository = mock(ToolApprovalRepository.class);
    var guard = guard();
    guard.setApprovals(repository);
    String input =
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"context\":\"review context\",\"sourceRefs\":[\"docs/design.md\"],\"expectedOutput\":\"{\\\"summary\\\":\\\"string\\\"}\"}";
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                guard.check(
                    "delegateTask", input, owner(AgentRunContext.Mode.EXCLUSIVE, "session")));
    assertTrue(error.getMessage().contains("exact-match"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(repository);
    assertTrue(error.getMessage().contains("request.accountRef"));
    assertTrue(error.getMessage().contains("request.modelId"));
    assertTrue(error.getMessage().contains("request.context"));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void invalidArgumentsNeverCreateOrConsumeApprovalEvenWithoutADenyList() {
    policy =
        new ToolPermissionPolicy(new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of()));
    var repository = mock(ToolApprovalRepository.class);
    var guard = guard();
    guard.setApprovals(repository);
    for (String input :
        List.of(
            "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"unknown\"}",
            "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"\",\"sourceRefs\":[\"docs/a.md\"],\"expectedOutput\":\"JSON\"}",
            "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":true}",
            "{\"agent\":\"reviewer\"}"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              guard.check("delegateTask", input, owner(AgentRunContext.Mode.EXCLUSIVE, "session")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            guard.check(
                "delegateTasks",
                "{\"requests\":[{\"id\":\"same\",\"agent\":\"reviewer\",\"task\":\"review\"},{\"id\":\"same\",\"agent\":\"reviewer\",\"task\":\"review\"}]}",
                owner(AgentRunContext.Mode.EXCLUSIVE, "session")));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(repository);
  }

  /** ツール利用の権限制御で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void localSingleAndParallelReviewsPassTheRealGuardWithDefaultPolicy() {
    var owner = owner(AgentRunContext.Mode.READ_ONLY, "session");
    assertDoesNotThrow(
        () ->
            guard()
                .check(
                    "delegateTask",
                    "{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}",
                    owner));
    assertDoesNotThrow(
        () ->
            guard()
                .check(
                    "delegateTasks",
                    "{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"},{\"agent\":\"judge\",\"task\":\"judge\",\"id\":\"judge\",\"backendId\":\"local\"}]}",
                    owner));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void requiredChildCapabilitiesAreCombinedBeforeAnyChildStarts() {
    String input =
        "{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"},{\"agent\":\"researcher\",\"task\":\"research\",\"id\":\"research\"}]}";
    assertEquals(
        Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ),
        policy.capabilities("delegateTasks", input));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("delegateTasks", input));
    assertThrows(
        ToolPermissionException.class,
        () ->
            guard()
                .check("delegateTasks", input, owner(AgentRunContext.Mode.EXCLUSIVE, "session")));
    assertEquals(
        PermissionDecision.AUTO_APPROVE,
        policy.evaluate(
            "delegateTask", "{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}"));
  }

  /** 実行を必要とすることを確認する。 */
  @Test
  void externalDestinationsRequireSendingAndAppServerRequiresExecution() {
    for (String tool : List.of("delegateTask", "delegateTasks")) {
      for (String backend : List.of("chatgpt-plan", "codex-app-server")) {
        String request = "{\"agent\":\"reviewer\",\"backendId\":\"" + backend + "\"}";
        String input =
            tool.equals("delegateTasks")
                ? "{\"requests\":[{\"agent\":\"judge\"}," + request + "]}"
                : request;
        var required = policy.capabilities(tool, input);
        assertTrue(
            required.containsAll(
                Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT)));
        assertEquals(
            backend.equals("codex-app-server"), required.contains(ActionCapability.EXECUTE));
        assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate(tool, input));
        assertThrows(
            ToolPermissionException.class,
            () -> guard().check(tool, input, owner(AgentRunContext.Mode.READ_ONLY, "session")));
      }
    }
  }

  /** ツール利用の権限制御で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unknownMalformedAndDisallowedChildrenFailClosed() {
    for (String input :
        List.of(
            "{}",
            "not json",
            "{\"agent\":\"missing\"}",
            "{\"agent\":\"writer\"}",
            "{\"agent\":\"reviewer\",\"backendId\":\"unknown\"}",
            "{\"agent\":\"reviewer\",\"backendId\":true}"))
      assertEquals(PermissionDecision.DENY, policy.evaluate("delegateTask", input));
    for (String input :
        List.of(
            "{\"requests\":[]}",
            "{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"},{\"agent\":\"missing\"}]}"))
      assertEquals(PermissionDecision.DENY, policy.evaluate("delegateTasks", input));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void disabledPolicyAndReadRelabelingCannotAuthorizeExternalOrRecursiveDelegation() {
    var resolver =
        policy.capabilities(
            "delegateTask", "{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}");
    assertEquals(Set.of(ActionCapability.READ), resolver);
    assertThrows(
        ToolPermissionException.class,
        () ->
            guard()
                .check(
                    "delegateTask",
                    "{\"agent\":\"reviewer\",\"task\":\"review\",\"id\":\"review\"}",
                    owner(AgentRunContext.Mode.EXCLUSIVE, "subagent:child")));
    var providers = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    providers.addBean("registry", registry);
    providers.addBean("children", new SubAgentToolPolicy(Set.of("readMultiFile", "webSearch")));
    policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                false, null, null, Map.of("delegateTask", Set.of(ActionCapability.READ))));
    policy.setDelegationResolver(
        new DelegationToolPermissionResolver(
            providers.getBeanProvider(SubAgentRegistry.class),
            providers.getBeanProvider(SubAgentToolPolicy.class)));
    assertThrows(
        ToolPermissionException.class,
        () ->
            guard()
                .check(
                    "delegateTask",
                    "{\"agent\":\"reviewer\",\"backendId\":\"chatgpt-plan\"}",
                    owner(AgentRunContext.Mode.READ_ONLY, "session")));
  }

  /** 関連図および評価の判断について、子の実行定義一覧を利用することを確認する。 */
  @Test
  void graphAndJudgeUseChildDefinitionsInsteadOfUnknownToolFallback() {
    assertEquals(
        PermissionDecision.AUTO_APPROVE,
        policy.evaluate("judgeSubAgentAnswers", "{\"agent\":\"judge\"}"));
    assertEquals(
        Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ, ActionCapability.LOCAL_WRITE),
        policy.capabilities(
            "delegateTaskGraph",
            "{\"spec\":{\"nodes\":[{\"agent\":\"researcher\",\"task\":\"research\",\"id\":\"research\"}]}}"));
  }
}
