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
 * @param execution 起動環境と出力取得の観測事実
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
    String errorMessage,
    dev.mikoto2000.rei.shellautomation.ShellExecutionObservation execution) {
  public RunCommandResult {
    errorMessage = BuildTestFailureDiagnosis.redactDiagnostic(errorMessage);
  }

  public void recordTerminal() {
    if (execution == null) return;
    var diagnosis = diagnosis();
    dev.mikoto2000.rei.shellautomation.ShellExecutionTelemetry.terminal(
        executionMode,
        execution.environment().shell(),
        diagnosis.outcome(),
        diagnosis.category(),
        diagnosis.evidence().stream().anyMatch(e -> e.source().equals("execution")));
  }

  public RunCommandResult(
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
    this(
        status,
        executionMode,
        resolvedExecutionMode,
        exitCode,
        stdout,
        stderr,
        processId,
        pid,
        timedOut,
        errorMessage,
        null);
  }

  /** 終了状態と出力から診断を組み立てる。 */
  @JsonProperty(value = "diagnosis", access = JsonProperty.Access.READ_ONLY)
  public BuildTestFailureDiagnosis diagnosis() {
    return BuildTestFailureDiagnosis.command(
        status, exitCode, timedOut, stdout, stderr, errorMessage, execution);
  }
}
