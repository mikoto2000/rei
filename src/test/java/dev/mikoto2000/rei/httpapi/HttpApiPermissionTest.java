package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Intrinsic write authority cannot be relabeled or inherited from read approval. */
class HttpApiPermissionTest {
  @Test
  void writeIsNeverReadOnlyAndCannotBeRelabeled() {
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(true,
        Set.of(ActionCapability.NETWORK_READ), Set.of(),
        Map.of("httpRequestWrite", Set.of(ActionCapability.NETWORK_READ))));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("httpRequestWrite"));
    assertFalse(policy.readOnly("httpRequestWrite", "{}"));
    assertFalse(ToolPermissionPolicy.intrinsicallyReadOnly("httpRequestWrite"));
    assertTrue(policy.capabilities("httpRequestWrite").contains(ActionCapability.NETWORK_WRITE));
  }

  @Test
  void readOwnerCannotUseWriteOrInheritParentsApproval() {
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(true,
        Set.of(ActionCapability.NETWORK_READ), Set.of(), Map.of()));
    var approvals = mock(ToolApprovalRepository.class);
    var guard = new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), event -> {});
    guard.setApprovals(approvals);
    var parent = new AgentRunContext("parent", "session", Path.of("."), "project",
        AgentRunContext.RequestSource.WEB, AgentRunContext.Mode.READ_ONLY);
    var child = new AgentRunContext("child", "subagent:session", Path.of("."), "project",
        AgentRunContext.RequestSource.WEB, AgentRunContext.Mode.READ_ONLY);
    assertDoesNotThrow(() -> guard.checkDelegated("httpRequestRead", "{}", child, parent));
    assertThrows(ToolPermissionException.class, () -> guard.checkDelegated("httpRequestWrite", "{}", child, parent));
    verifyNoInteractions(approvals);
    var allowlist = new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(Set.of("httpRequestRead", "httpRequestWrite"));
    assertThrows(IllegalArgumentException.class, () -> allowlist.validate(java.util.List.of("httpRequestWrite")));
  }

  @Test
  void legacyPermissionSettingStillRequiresApprovalForNewWriteTool() {
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("httpRequestWrite"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("httpRequestWrite", "{}"));
  }
}
