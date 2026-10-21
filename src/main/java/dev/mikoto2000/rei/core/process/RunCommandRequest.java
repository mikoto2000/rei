package dev.mikoto2000.rei.core.process;

/**
 * 通常利用向け Shell command 実行要求。
 *
 * @param command コマンド
 * @param executionMode 実行動作モード
 * @param timeoutSeconds タイムアウトの秒数
 */
public record RunCommandRequest(String command, String executionMode, Integer timeoutSeconds) {

  /** 受け取った情報からRunCommandRequestを作成する。 */
  public RunCommandRequest {
    if (command == null || command.isBlank())
      throw new IllegalArgumentException("command は空にできません");
    CommandExecutionMode.parse(executionMode);
  }

  /** 外部プロセスの管理で用いる動作モードを返す。 */
  public CommandExecutionMode mode() {
    return CommandExecutionMode.parse(executionMode);
  }
}
