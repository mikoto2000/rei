package dev.mikoto2000.rei.core.process;

/** 通常利用向け Shell command 実行要求。 */
public record RunCommandRequest(String command, String executionMode, Integer timeoutSeconds) {

  public RunCommandRequest {
    if (command == null || command.isBlank()) throw new IllegalArgumentException("command は空にできません");
    CommandExecutionMode.parse(executionMode);
  }

  public CommandExecutionMode mode() {
    return CommandExecutionMode.parse(executionMode);
  }
}
