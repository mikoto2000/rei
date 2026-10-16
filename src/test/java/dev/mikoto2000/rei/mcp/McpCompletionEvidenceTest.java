package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpCompletionEvidenceTest {
  @Test
  void executionSuccessDoesNotInventVerification() {
    var result = McpCompletionEvidence.project(true, List.of("one", "two"), Set.of(), false);
    assertEquals("UNVERIFIED", result.get("acceptance_status"));
    assertEquals(true, result.get("execution_succeeded"));
  }

  @Test
  void partialAndFullVerificationRequireSavedMatchingCriteria() {
    assertEquals(
        "PARTIALLY_VERIFIED",
        McpCompletionEvidence.project(true, List.of("one", "two"), Set.of("one"), false)
            .get("acceptance_status"));
    assertEquals(
        "VERIFIED",
        McpCompletionEvidence.project(true, List.of("one", "two"), Set.of("one", "two"), false)
            .get("acceptance_status"));
    assertEquals(
        "FAILED",
        McpCompletionEvidence.project(false, List.of("one"), Set.of(), true)
            .get("acceptance_status"));
    assertEquals(
        "UNKNOWN",
        McpCompletionEvidence.project(true, List.of(), Set.of(), false).get("acceptance_status"));
  }
}
