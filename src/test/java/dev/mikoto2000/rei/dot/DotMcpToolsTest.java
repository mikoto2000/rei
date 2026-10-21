package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示MCPツール一覧の結果と状態の契約を検証するテスト。 */
class DotMcpToolsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うリポジトリ。 */
  DotRequestRepository repo;

  /** 検証に使うツール一覧。 */
  DotMcpTools tools;

  /** 検証に使う識別子。 */
  String id;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
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

  /** {@code data} の条件を再現するための補助情報を返す。 */
  @SuppressWarnings("unchecked")
  Map<String, Object> data(io.modelcontextprotocol.spec.McpSchema.CallToolResult r) {
    return (Map<String, Object>) r.structuredContent();
  }

  /** 利用者の状態表示で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
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

  /** 利用者の状態表示の操作が、指定された権限・承認条件に従うことを確認する。 */
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

  /** 利用者の状態表示で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
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
