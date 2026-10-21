package dev.mikoto2000.rei.core.process;

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
 * @param diagnosis 診断
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
    BuildTestFailureDiagnosis diagnosis) {
  /** 外部プロセスの管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
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

  /** 受け取った情報からRunCommandResultを作成する。 */
  public RunCommandResult {
    diagnosis =
        BuildTestFailureDiagnosis.command(status, exitCode, timedOut, stdout, stderr, errorMessage);
  }
}
