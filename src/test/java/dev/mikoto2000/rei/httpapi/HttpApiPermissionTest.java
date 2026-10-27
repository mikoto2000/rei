package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
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
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                Set.of(ActionCapability.NETWORK_READ),
                Set.of(),
                Map.of("httpRequestWrite", Set.of(ActionCapability.NETWORK_READ))));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("httpRequestWrite"));
    assertFalse(policy.readOnly("httpRequestWrite", "{}"));
    assertFalse(ToolPermissionPolicy.intrinsicallyReadOnly("httpRequestWrite"));
    assertTrue(policy.capabilities("httpRequestWrite").contains(ActionCapability.NETWORK_WRITE));
  }

  @Test
  void readOwnerCannotUseWriteOrInheritParentsApproval() {
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true, Set.of(ActionCapability.NETWORK_READ), Set.of(), Map.of()));
    var approvals = mock(ToolApprovalRepository.class);
    var guard =
        new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), event -> {});
    guard.setApprovals(approvals);
    var parent =
        new AgentRunContext(
            "parent",
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.WEB,
            AgentRunContext.Mode.READ_ONLY);
    var child =
        new AgentRunContext(
            "child",
            "subagent:session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.WEB,
            AgentRunContext.Mode.READ_ONLY);
    assertDoesNotThrow(() -> guard.checkDelegated("httpRequestRead", "{}", child, parent));
    assertThrows(
        ToolPermissionException.class,
        () -> guard.checkDelegated("httpRequestWrite", "{}", child, parent));
    verifyNoInteractions(approvals);
    var allowlist =
        new dev.mikoto2000.rei.subagent.SubAgentToolPolicy(
            Set.of("httpRequestRead", "httpRequestWrite"));
    assertThrows(
        IllegalArgumentException.class,
        () -> allowlist.validate(java.util.List.of("httpRequestWrite")));
  }

  @Test
  void legacyPermissionSettingStillRequiresApprovalForNewWriteTool() {
    var policy = new ToolPermissionPolicy(new ToolPermissionProperties(false, null, null, null));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("httpRequestWrite"));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, policy.evaluate("httpRequestWrite", "{}"));
  }

  @Test
  void legacyReadToolStillChecksHostPermissionWithoutRunContext() {
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true, Set.of(), Set.of(ActionCapability.NETWORK_READ), null));
    var guard =
        new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), event -> {});
    var service = mock(HttpApiService.class);
    var tools = new HttpApiTools(service, guard);
    assertThrows(
        ToolPermissionException.class,
        () ->
            tools.httpRequestRead(
                new HttpApiRequest("https://example.com", null, null, null, null, null, null, null),
                new org.springframework.ai.chat.model.ToolContext(Map.of("legacy", true))));
    verifyNoInteractions(service);
  }

  @Test
  void writeApprovalUsesExactInputAndRejectionDoesNotExecute() {
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true, Set.of(ActionCapability.NETWORK_READ), Set.of(), null));
    var repository = mock(ToolApprovalRepository.class);
    var guard =
        new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), event -> {});
    guard.setApprovals(repository);
    var owner =
        new AgentRunContext(
            "run",
            "session",
            Path.of("."),
            "project",
            AgentRunContext.RequestSource.WEB,
            AgentRunContext.Mode.EXCLUSIVE);
    String input = "{\"request\":{\"url\":\"https://example.com/tasks/1\",\"method\":\"DELETE\"}}";
    when(repository.consume("httpRequestWrite", input, owner)).thenReturn(true);
    assertDoesNotThrow(() -> guard.check("httpRequestWrite", input, owner));
    when(repository.consume("httpRequestWrite", input, owner)).thenReturn(false);
    when(repository.request("httpRequestWrite", input, owner))
        .thenReturn(
            new ToolApprovalRepository.Request(
                "approval",
                "project",
                "session",
                "run",
                "httpRequestWrite",
                input,
                "PENDING",
                java.time.Instant.now().plusSeconds(60)));
    assertThrows(
        ToolPermissionException.class, () -> guard.check("httpRequestWrite", input, owner));
  }
}
