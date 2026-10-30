package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.computeruse.FullAutoOptions;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/** 自動実行の指定ツール権限の結果と状態の契約を検証するテスト。 */
class FullAutoToolPermissionTest {
  /** 起動時の自動実行指定を共通の権限判断へ反映し、明示的な禁止を優先することを確認する。 */
  @Test
  void springWiresStartupArgumentsIntoCommonPolicy() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(ToolPermissionPolicy.class, FullAutoOptions.class)
        .withBean(WebSearchProperties.class, WebSearchProperties::new)
        .withBean(
            org.springframework.boot.ApplicationArguments.class,
            () -> new DefaultApplicationArguments("--fullauto"))
        .withBean(
            dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.class,
            () ->
                org.mockito.Mockito.mock(
                    dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.class))
        .withPropertyValues("rei.tool-permission.enabled=true")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              var policy = context.getBean(ToolPermissionPolicy.class);
              assertTrue(policy.fullAuto());
              assertEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate("writeMultiFile"));
              assertEquals(PermissionDecision.DENY, policy.evaluate("deleteFile"));
            });
  }

  /** 検証で使用する判断基準を、指定された条件に合わせて用意する。 */
  private ToolPermissionPolicy policy(String... args) {
    return new ToolPermissionPolicy(
        new ToolPermissionProperties(true, Set.of(ActionCapability.READ), Set.of(), Map.of()),
        new FullAutoOptions(new DefaultApplicationArguments(args)));
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void startupFlagApprovesCommonToolsIncludingComputerUseAndUnknownCallbacks() {
    for (String flag : List.of("--fullauto", "--fullauto=true")) {
      var policy = policy(flag);
      for (String tool :
          List.of("writeMultiFile", "runCommand", "computerUse", "unknownMcpTool", "blueskyPost"))
        assertEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate(tool, "{}"), tool);
      assertTrue(policy.fullAuto());
    }
    for (var policy : List.of(policy(), policy("--fullauto=false"))) {
      assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("computerUse"));
      assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("writeMultiFile"));
    }
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void configuredDenialStillWinsAndAuthorityReflectsStartupFlag() {
    var properties = new ToolPermissionProperties(true, null, null, null);
    var enabled =
        new ToolPermissionPolicy(
            properties, new FullAutoOptions(new DefaultApplicationArguments("--fullauto")));
    assertEquals(PermissionDecision.DENY, enabled.evaluate("deleteFile"));
    assertEquals(PermissionDecision.DENY, enabled.evaluate("computerUse"));
    assertEquals(PermissionDecision.AUTO_APPROVE, enabled.evaluate("writeMultiFile"));
    assertNotEquals(
        new ToolPermissionPolicy(properties).authorityFingerprint(),
        enabled.authorityFingerprint());
    assertEquals(
        policy().authorityFingerprint(), policy("--fullauto=false").authorityFingerprint());
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void fullAutoIsExplicitAuthorityEvenWhenConfiguredPolicyIsDisabled() {
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(false, null, null, null),
            new FullAutoOptions(new DefaultApplicationArguments("--fullauto")));
    assertTrue(policy.enforced());
    assertEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate("requestCodexImplementation"));
    var guard =
        new ToolPermissionGuard(
            policy, new AgentEventFactory(Clock.systemUTC()), event -> fail("Unexpected approval"));
    var voice = new AgentRunContext("run", "session", Path.of("."), "project").asVoiceInput();
    assertDoesNotThrow(
        () ->
            guard.check(
                "writeMultiFile",
                "{\"files\":[{\"path\":\"a.txt\",\"content\":\"fixture\"}]}",
                voice));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void textVoiceAndLocalChildExecuteWithoutCreatingApprovalRequests() {
    var events = new ArrayList<AgentEvent>();
    var guard =
        new ToolPermissionGuard(
            policy("--fullauto"), new AgentEventFactory(Clock.systemUTC()), events::add);
    var approvals = org.mockito.Mockito.mock(ToolApprovalRepository.class);
    guard.setApprovals(approvals);
    var text = new AgentRunContext("run", "session", Path.of("."), "project");
    var child = new AgentRunContext("child", "subagent:child", Path.of("."), "project");
    for (var owner : List.of(text, text.asVoiceInput(), child))
      assertDoesNotThrow(() -> guard.check("computerUse", "{}", owner));
    assertTrue(events.isEmpty());
    org.mockito.Mockito.verifyNoInteractions(approvals);
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void fullAutoDoesNotBypassExecutionModeOrMcpRestrictions() {
    var guard =
        new ToolPermissionGuard(
            policy("--fullauto"), new AgentEventFactory(Clock.systemUTC()), event -> {});
    for (var mode : List.of(AgentRunContext.Mode.READ_ONLY, AgentRunContext.Mode.CONVERSATION)) {
      var owner =
          new AgentRunContext(
              "run", "session", Path.of("."), "project", AgentRunContext.RequestSource.SHELL, mode);
      assertThrows(
          ToolPermissionException.class,
          () ->
              guard.check(
                  "writeMultiFile",
                  "{\"files\":[{\"path\":\"a.txt\",\"content\":\"fixture\"}]}",
                  owner));
    }
    var mcp =
        new AgentRunContext(
            "run",
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.MCP,
            AgentRunContext.Mode.READ_ONLY);
    assertThrows(ToolPermissionException.class, () -> guard.check("readFile", "{}", mcp));
    assertDoesNotThrow(() -> guard.check("readMultiFile", "{}", mcp));
  }
}
