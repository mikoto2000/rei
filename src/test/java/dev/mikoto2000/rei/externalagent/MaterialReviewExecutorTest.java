package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** 教材レビュー実行先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MaterialReviewExecutorTest {
  /** 検証に使う作業ルート。 */
  @org.junit.jupiter.api.io.TempDir Path root;

  /** レポートを保持することを確認する。 */
  @Test
  void selectsMaterialPromptSchemaAndPreservesReportForEvaluation() throws Exception {
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              Path cwd,
              String input,
              Duration total,
              Duration idle,
              int limit,
              BooleanSupplier cancelled) {
            if (command.contains("--help")) return capabilities();
            assertTrue(input.contains("Instructional Design"));
            assertTrue(input.contains("全ページ読了後"));
            assertTrue(input.contains("untrusted input"));
            Path schema = Path.of(command.get(command.indexOf("--output-schema") + 1));
            try {
              assertTrue(Files.readString(schema).contains("reviewedFiles"));
              return output(MaterialReviewSpecificationTest.fixture(), false);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          }
        };
    var result =
        new CodexExternalAgentExecutor(new CodexProperties(), runner)
            .execute(request(), () -> false);
    assertTrue(result.success());
    String evaluated =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(result.forEvaluation());
    assertTrue(evaluated.contains("# 勉強会資料レビュー"));
    assertTrue(evaluated.contains("docs/setup.mdx"));
    assertTrue(evaluated.contains("NOT_RUN"));
    assertTrue(evaluated.contains("再現可能なコード例"));
    assertTrue(evaluated.contains("ページ間で接続先の設定が矛盾"));
    assertTrue(evaluated.contains("導入と設定ページの値を統一する"));
    assertTrue(evaluated.contains("4 / 5"));
    assertTrue(evaluated.contains("根拠のある評価"));
    for (String section :
        List.of(
            "1. Executive Summary",
            "2. 対象範囲",
            "3. 総合評価",
            "4. Critical Issues",
            "5. High Priority Issues",
            "6. Medium Priority Issues",
            "7. Low Priority Issues",
            "8. ファイル別レビュー",
            "9. ページ横断の問題",
            "10. 不足している説明",
            "11. 図・コード例・デモを追加するとよい箇所",
            "12. 削減または Appendix 化を推奨する内容",
            "13. 推奨する修正順序",
            "14. Build / Link / Markdown 検証結果",
            "15. 良い点")) assertTrue(evaluated.contains("## " + section), section);
    assertEquals(ExternalAgentFinding.Severity.high, result.findings().getFirst().severity());
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidAndTruncatedResultsFailWithoutRetry() throws Exception {
    String valid = MaterialReviewSpecificationTest.fixture();
    for (String text :
        List.of(
            "{broken",
            valid.replace("\"HIGH\"", "\"unknown\""),
            valid.replace("\"score\": 4", "\"score\": 6"),
            valid.replace("\"summary\"", "\"missing\""),
            valid + " {}",
            "{\"summary\":\"first\",\"summary\":\"duplicate\"}")) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var runner =
          new ExternalAgentProcessRunner() {
            /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
            @Override
            public Output run(
                List<String> command,
                Path cwd,
                String input,
                Duration total,
                Duration idle,
                int limit,
                BooleanSupplier cancelled) {
              calls.incrementAndGet();
              return command.contains("--help") ? capabilities() : output(text, false);
            }
          };
      var result =
          new CodexExternalAgentExecutor(new CodexProperties(), runner)
              .execute(request(), () -> false);
      assertEquals(ExternalAgentResult.Status.FAILED, result.status(), text);
      assertEquals(2, calls.get());
    }
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          @Override
          public Output run(
              List<String> command,
              Path cwd,
              String input,
              Duration total,
              Duration idle,
              int limit,
              BooleanSupplier cancelled) {
            return command.contains("--help") ? capabilities() : output(valid, true);
          }
        };
    assertEquals(
        ExternalAgentResult.Status.FAILED,
        new CodexExternalAgentExecutor(new CodexProperties(), runner)
            .execute(request(), () -> false)
            .status());
  }

  /** 外部エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void preservesProcessFailureTimeoutAndCancellation() {
    for (var status :
        List.of(
            ExternalAgentResult.Status.FAILED,
            ExternalAgentResult.Status.TOTAL_TIMEOUT,
            ExternalAgentResult.Status.INACTIVITY_TIMEOUT,
            ExternalAgentResult.Status.CANCELLED)) {
      var runner =
          new ExternalAgentProcessRunner() {
            /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
            @Override
            public Output run(
                List<String> command,
                Path cwd,
                String input,
                Duration total,
                Duration idle,
                int limit,
                BooleanSupplier cancelled) {
              return command.contains("--help")
                  ? capabilities()
                  : new Output(status, "", "", 1, 5, false);
            }
          };
      assertEquals(
          status,
          new CodexExternalAgentExecutor(new CodexProperties(), runner)
              .execute(request(), () -> false)
              .status());
    }
  }

  /** 検証で使用する要求を、指定された条件に合わせて用意する。 */
  ExternalAgentRequest request() {
    return new ExternalAgentRequest(
        ExternalAgentRequest.Agent.CODEX,
        ExternalAgentRequest.Action.MATERIAL_REVIEW,
        "material",
        root,
        null,
        "",
        "run",
        "id");
  }

  /** {@code capabilities} の条件を再現するための補助情報を返す。 */
  static ExternalAgentProcessRunner.Output capabilities() {
    return new ExternalAgentProcessRunner.Output(
        ExternalAgentResult.Status.SUCCESS,
        "--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json",
        "",
        0,
        1,
        false);
  }

  /** {@code output} の条件を再現するための補助情報を返す。 */
  static ExternalAgentProcessRunner.Output output(String text, boolean truncated) {
    try {
      String event =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "type",
                      "item.completed",
                      "item",
                      Map.of("type", "agent_message", "text", text)));
      return new ExternalAgentProcessRunner.Output(
          ExternalAgentResult.Status.SUCCESS, event, "", 0, 3, truncated);
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }
}
