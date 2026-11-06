package dev.mikoto2000.rei.core.process;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * runCommand の正規化済み結果。
 *
 * @param status 状態
 * @param executionMode 実行動作モード
 * @param resolvedExecutionMode 確定した実行動作モード
 * @param exitCode 終了コード
 * @param stdout 標準出力
 * @param stderr 標準エラー出力
 * @param processId プロセス識別子
 * @param pid プロセス識別子
 * @param timedOut timed出力先
 * @param errorMessage エラーメッセージ
 */
public record RunCommandResult(
    String status,
    String executionMode,
    String resolvedExecutionMode,
    Integer exitCode,
    String stdout,
    String stderr,
    String processId,
    Long pid,
    boolean timedOut,
    String errorMessage) {
  /** 終了状態と出力から診断を組み立てる。 */
  @JsonProperty(value = "diagnosis", access = JsonProperty.Access.READ_ONLY)
  public BuildTestFailureDiagnosis diagnosis() {
    return BuildTestFailureDiagnosis.command(status, exitCode, timedOut, stdout, stderr, errorMessage);
  }
}
