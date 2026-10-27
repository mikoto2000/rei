package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Recognizable shell HTTP calls cannot inherit a narrowed EXECUTE-only grant. */
class ShellHttpPermissionTest {
  @Test
  void deniedNetworkAuthoritySurvivesShellToolOverride() {
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                Set.of(ActionCapability.EXECUTE),
                Set.of(ActionCapability.NETWORK_WRITE),
                Map.of("runCommand", Set.of(ActionCapability.EXECUTE))));
    assertEquals(
        PermissionDecision.AUTO_APPROVE,
        policy.evaluate("runCommand", "{\"request\":{\"command\":\"git status\"}}"));
    for (String command :
        java.util.List.of(
            "Invoke-RestMethod https://example.com",
            "irm https://example.com",
            "curl.exe https://example.com",
            "python api.py https://example.com")) {
      String input = "{\"request\":{\"command\":\"" + command + "\"}}";
      assertEquals(PermissionDecision.DENY, policy.evaluate("runCommand", input));
      assertTrue(ShellHttpUsage.recognizable(command));
    }
  }

  @Test
  void toolGuidancePrioritizesDedicatedAndStructuredApiTools() {
    String guidance = dev.mikoto2000.rei.core.configuration.ToolUsagePolicy.INSTRUCTIONS;
    assertTrue(guidance.contains("httpRequestRead"));
    assertTrue(guidance.contains("httpRequestWrite"));
    assertTrue(guidance.contains("bypass"));
  }
}
