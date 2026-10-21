package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** MCP完了根拠の結果と状態の契約を検証するテスト。 */
class McpCompletionEvidenceTest {
  /** MCPによる機能の提供で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void executionSuccessDoesNotInventVerification() {
    var result = McpCompletionEvidence.project(true, List.of("one", "two"), Set.of(), false);
    assertEquals("UNVERIFIED", result.get("acceptance_status"));
    assertEquals(true, result.get("execution_succeeded"));
  }

  /** 部分的な結果および全体確認結果について、保存済み一致する判断基準一覧を必要とすることを確認する。 */
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
