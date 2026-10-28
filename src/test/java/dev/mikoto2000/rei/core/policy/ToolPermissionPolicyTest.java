package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ツール権限判断基準の結果と状態の契約を検証するテスト。 */
class ToolPermissionPolicyTest {
  @Test
  void durableReviewToolsUseExistingReadOnlyReviewPermission() {
    var policy = policy(Set.of(ActionCapability.READ), Set.of());
    for (var name : List.of("listReviewTasks", "resumeRepositoryReview", "claimReviewWorkItem", "readReviewWorkItem", "submitReviewWorkItem"))
      assertEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate(name), name);
  }

  @Test
  void externalWritesCannotBeRelabeledAsReadsAndCancelRequiresDestructivePermission() {
    var readOnly =
        policy(
            Set.of(ActionCapability.READ, ActionCapability.NETWORK_READ),
            Set.of(ActionCapability.NETWORK_WRITE));
    assertEquals("AUTO_APPROVE", readOnly.decision("EXTERNAL_READ").decision());
    assertEquals("DENY", readOnly.decision("EXTERNAL_MAIL_SEND").decision());
    var writes =
        policy(
            Set.of(
                ActionCapability.NETWORK_READ,
                ActionCapability.NETWORK_WRITE,
                ActionCapability.EXTERNAL_SIDE_EFFECT),
            Set.of(ActionCapability.DESTRUCTIVE));
    assertEquals("AUTO_APPROVE", writes.decision("EXTERNAL_CALENDAR_UPDATE").decision());
    assertEquals("DENY", writes.decision("EXTERNAL_CALENDAR_CANCEL").decision());
  }

  /** 追加された検索上限を利用することを確認する。 */
  @Test
  void webSearchPreflightUsesInjectedSearchLimit() {
    var policy = policy(Set.of(), Set.of());
    var properties = new dev.mikoto2000.rei.websearch.WebSearchProperties();
    properties.setMaxResults(8);
    policy.setWebSearchProperties(properties);
    String input = "{\"request\":{\"query\":\"Void\",\"maxResults\":8,\"readTop\":8}}";
    assertDoesNotThrow(() -> policy.validateArguments("webSearchAndRead", input));
    properties.setMaxResults(3);
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () -> policy.validateArguments("webSearchAndRead", input));
    assertTrue(error.getMessage().contains("between 1 and 3"));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void administratorConfigurationBindsCapabilitiesAndDefaults() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of(
                "rei.tool-permission.enabled", "true",
                "rei.tool-permission.auto-approve[0]", "READ",
                "rei.tool-permission.auto-approve[1]", "NETWORK_READ",
                "rei.tool-permission.capabilities.my-read-tool[0]", "NETWORK_READ"));
    var props =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind("rei.tool-permission", ToolPermissionProperties.class)
            .get();
    var p = new ToolPermissionPolicy(props);
    assertEquals(PermissionDecision.AUTO_APPROVE, p.evaluate("my-read-tool"));
    assertEquals(PermissionDecision.DENY, p.evaluate("runCommand"));
  }

  /** 検証で使用する判断基準を、指定された条件に合わせて用意する。 */
  ToolPermissionPolicy policy(Set<ActionCapability> automatic, Set<ActionCapability> denied) {
    return new ToolPermissionPolicy(
        new ToolPermissionProperties(true, automatic, denied, Map.of()));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void readCanBeAutomaticButUnknownToolsRequireApproval() {
    var policy = policy(Set.of(ActionCapability.READ), Set.of());
    assertEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate("readMultiFile"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("unknownMcpTool"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("applyTextDiff"));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void denialWinsAndAllCapabilitiesMustBeGranted() {
    var p =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                Set.of(ActionCapability.EXECUTE),
                Set.of(ActionCapability.DESTRUCTIVE),
                Map.of("custom", Set.of(ActionCapability.EXECUTE, ActionCapability.DESTRUCTIVE))));
    assertEquals(PermissionDecision.DENY, p.evaluate("custom"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, p.evaluate("webSearch"));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void configuredCapabilitiesAreImmutableAndCanGrantNetworkRead() {
    var grants = new HashSet<>(Set.of(ActionCapability.NETWORK_READ));
    var p = policy(grants, Set.of());
    grants.clear();
    assertEquals(PermissionDecision.AUTO_APPROVE, p.evaluate("webSearch"));
  }

  /** 旧形式動作モードについて、既存のツール作業習慣を保持することを確認する。 */
  @Test
  void legacyModeRetainsExistingToolBehavior() {
    var p = new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null));
    assertEquals(PermissionDecision.AUTO_APPROVE, p.evaluate("unknownMcpTool"));
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaultEnforcedProfileDeniesUnclassifiedAndDestructiveActions() {
    var p = new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null));
    assertEquals(PermissionDecision.DENY, p.evaluate("runCommand"));
    assertEquals(PermissionDecision.DENY, p.evaluate("deleteFile"));
    assertEquals(PermissionDecision.DENY, p.evaluate("unknownMcpTool"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, p.evaluate("searchKnowledge"));
  }

  /** 加工前のツールの結果について、許可を読み取ることを確認する。 */
  @Test
  void rawToolResultReadIsAllowedByDefaultEnforcedProfile() {
    var p = new ToolPermissionPolicy(new ToolPermissionProperties(true, null, null, null));
    assertEquals(PermissionDecision.AUTO_APPROVE, p.evaluate("readRawToolResult"));
    assertTrue(ToolPermissionPolicy.intrinsicallyReadOnly("readRawToolResult"));
  }

  /** ツール利用の権限制御で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void eventStorageFailureStillPreventsExecution() {
    var guard =
        new ToolPermissionGuard(
            policy(Set.of(), Set.of()),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {
              throw new IllegalStateException("storage failure");
            });
    assertThrows(IllegalStateException.class, () -> guard.check("readMultiFile", null));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void guardReportsApprovalBeforeAnySideEffect() {
    List<AgentEvent> events = new ArrayList<>();
    var guard =
        new ToolPermissionGuard(
            policy(Set.of(ActionCapability.READ), Set.of()),
            new AgentEventFactory(Clock.systemUTC()),
            events::add);
    assertThrows(ToolPermissionException.class, () -> guard.check("applyTextDiff", null));
    assertEquals(1, events.size());
    var failure = (ToolFailedPayload) events.getFirst().payload();
    assertEquals("PermissionRequired", failure.error().errorType());
    assertDoesNotThrow(() -> guard.check("readMultiFile", null));
    assertEquals(1, events.size());
  }
}
