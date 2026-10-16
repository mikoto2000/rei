package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DotPolicyTest {
  @Test
  void administratorCannotRelabelDelegationAsReadAndReadOnlyCannotWriteInbox() {
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                Set.of(ActionCapability.READ),
                Set.of(),
                Map.of("requestDotResearch", Set.of(ActionCapability.READ))));
    assertEquals(
        Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
        policy.capabilities("requestDotResearch"));
    assertNotEquals(PermissionDecision.AUTO_APPROVE, policy.evaluate("requestDotResearch"));
    var guard = new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), e -> {});
    var owner =
        new AgentRunContext(
            "run",
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.MCP,
            AgentRunContext.Mode.READ_ONLY);
    for (String tool :
        List.of(
            "requestDotResearch",
            "rei_request_claim",
            "rei_request_result_submit",
            "executeShellCommand",
            "writeMultiFile"))
      assertThrows(ToolPermissionException.class, () -> guard.check(tool, "{}", owner), tool);
  }

  @Test
  void settingsRefuseAutomaticResumeOrIncreasedSafetyLimits() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DotProperties(true, true, true, null, null, 0, 0, null, null, 0, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DotProperties(true, true, false, null, null, 3, 8, null, null, 0, 0));
  }
}
