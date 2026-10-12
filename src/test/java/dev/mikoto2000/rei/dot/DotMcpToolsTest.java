package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DotMcpToolsTest {
  @TempDir Path root;
  DotRequestRepository repo;
  DotMcpTools tools;
  String id;

  @BeforeEach
  void start() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, Clock.systemUTC());
    tools = new DotMcpTools(repo, Set.of("allowed"), true);
    id =
        (String)
            repo.create(
                    "allowed",
                    "session",
                    null,
                    root.toString(),
                    Map.of(
                        "category",
                        "TECH_RESEARCH",
                        "instruction",
                        "Research",
                        "acceptance_criteria",
                        List.of("Verify")),
                    Duration.ofHours(1))
                .get("request_id");
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> data(io.modelcontextprotocol.spec.McpSchema.CallToolResult r) {
    return (Map<String, Object>) r.structuredContent();
  }

  @Test
  void mcpRoundTripPersistsOnlyPendingReview() {
    assertEquals(
        1, ((List<?>) data(tools.call("rei_requests_list", Map.of())).get("requests")).size());
    assertFalse(tools.call("rei_request_get", Map.of("request_id", id)).isError());
    String claim = UUID.randomUUID().toString(), submission = UUID.randomUUID().toString();
    assertFalse(
        tools.call("rei_request_claim", Map.of("request_id", id, "claim_id", claim)).isError());
    var input =
        Map.<String, Object>of(
            "request_id",
            id,
            "claim_id",
            claim,
            "submission_id",
            submission,
            "outcome",
            "COMPLETED",
            "summary",
            "Report");
    var first = tools.call("rei_request_result_submit", input);
    assertFalse(first.isError());
    assertEquals("PENDING_REVIEW", data(first).get("validation_status"));
    assertEquals(data(first), data(tools.call("rei_request_result_submit", input)));
  }

  @Test
  void unauthorizedAndInvalidRequestsAreSafeErrors() {
    var denied = new DotMcpTools(repo, Set.of(), true);
    assertTrue(denied.call("rei_request_get", Map.of("request_id", id)).isError());
    assertTrue(tools.call("rei_requests_list", Map.of("limit", 1.5)).isError());
    assertTrue(
        tools.call("rei_request_get", Map.of("request_id", id, "root", "C:\\secret")).isError());
    assertTrue(tools.call("rei_requests_list", Map.of("cursor", "forged")).isError());
    assertTrue(
        new DotMcpTools(repo, Set.of("allowed"), false)
            .call("rei_requests_list", Map.of())
            .isError());
  }

  @Test
  void malformedEvidenceAndOversizeResultsAreRejected() {
    String claim = UUID.randomUUID().toString();
    tools.call("rei_request_claim", Map.of("request_id", id, "claim_id", claim));
    var input =
        new HashMap<String, Object>(
            Map.of(
                "request_id",
                id,
                "claim_id",
                claim,
                "submission_id",
                UUID.randomUUID().toString(),
                "outcome",
                "COMPLETED",
                "summary",
                "Report",
                "content",
                "x".repeat(65537)));
    assertTrue(tools.call("rei_request_result_submit", input).isError());
    input.put("content", "report");
    input.put(
        "evidence",
        List.of(Map.of("title", "bad", "url", "file:///C:/secret", "description", "unsafe")));
    assertTrue(tools.call("rei_request_result_submit", input).isError());
  }
}
