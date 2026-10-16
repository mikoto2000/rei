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
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class FullAutoToolPermissionTest {
  @Test
  void springWiresStartupArgumentsIntoCommonPolicy() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(ToolPermissionPolicy.class, FullAutoOptions.class)
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

  private ToolPermissionPolicy policy(String... args) {
    return new ToolPermissionPolicy(
        new ToolPermissionProperties(true, Set.of(ActionCapability.READ), Set.of(), Map.of()),
        new FullAutoOptions(new DefaultApplicationArguments(args)));
  }

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
    assertDoesNotThrow(() -> guard.check("writeMultiFile", "{}", voice));
  }

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

  @Test
  void fullAutoDoesNotBypassExecutionModeOrMcpRestrictions() {
    var guard =
        new ToolPermissionGuard(
            policy("--fullauto"), new AgentEventFactory(Clock.systemUTC()), event -> {});
    for (var mode : List.of(AgentRunContext.Mode.READ_ONLY, AgentRunContext.Mode.CONVERSATION)) {
      var owner =
          new AgentRunContext(
              "run", "session", Path.of("."), "project", AgentRunContext.RequestSource.SHELL, mode);
      assertThrows(ToolPermissionException.class, () -> guard.check("writeMultiFile", "{}", owner));
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
