package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** ツールの結果Compressorの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ToolResultCompressorTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使うトークン数。 */
  final TokenEstimator tokens = TokenEstimator.conservative();

  /** 検証で使用する結果を、指定された条件に合わせて用意する。 */
  Message result(String text) {
    return ToolResponseMessage.builder()
        .responses(List.of(new ToolResponseMessage.ToolResponse("call", "runCommand", text)))
        .build();
  }

  /** モデルへ渡す文脈の管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void smallResultsStayUnchanged() {
    var message = result("exitCode: 0");
    assertThat(
            new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70)
                .compact(message, "chat", "run"))
        .isSameAs(message);
  }

  /** コマンドJSON変換について、項目に整理した内容診断を維持することを確認する。 */
  @Test
  void commandJsonKeepsStructuredDiagnosisBeforeVerboseProgressLogs() {
    var command =
        new dev.mikoto2000.rei.core.process.RunCommandResult(
            "failed",
            "foreground",
            "foreground",
            1,
            "progress\\n".repeat(3000),
            "[ERROR] demo.AppTest.fail -- Time elapsed: 0.1 s <<< FAILURE!",
            null,
            null,
            false,
            null);
    String raw =
        new org.springframework.ai.tool.execution.DefaultToolCallResultConverter()
            .convert(command, null);
    var compact =
        (ToolResponseMessage)
            new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 600)
                .compact(result(raw), "chat", "run");
    assertThat(compact.getResponses().getFirst().responseData())
        .contains("TEST_FAILURE", "demo.AppTest.fail");
  }

  /** 大きな結果一覧について、診断情報を維持することを確認する。 */
  @Test
  void largeResultsKeepDiagnosticsAndRetrievableRawBody() {
    String raw =
        "command: ./mvnw test\nstatus: FAILED\nexitCode: 1\n"
            + "progress\n".repeat(500)
            + "NativeSessionClientTest failed\nexpected 42 but was 41\nBUILD FAILURE\n";
    var store = new RawToolResultStore(dir);
    var original = result(raw);
    var compact =
        (ToolResponseMessage)
            new ToolResultCompressor(store, tokens, 100, 300).compact(original, "chat", "run");
    String text = compact.getResponses().getFirst().responseData();
    assertThat(text).contains("exitCode: 1", "status: FAILED", "expected 42", "rawResultRef:");
    String ref =
        text.lines()
            .filter(s -> s.startsWith("rawResultRef: "))
            .findFirst()
            .orElseThrow()
            .substring(14);
    assertThat(new RawToolResultStore(dir).read("chat", ref).rawResult()).isEqualTo(raw);
    assertThat(tokens.message(compact)).isLessThan(tokens.message(original));
    assertThatThrownBy(() -> store.read("another conversation", ref))
        .isInstanceOf(IllegalStateException.class);
  }
}
