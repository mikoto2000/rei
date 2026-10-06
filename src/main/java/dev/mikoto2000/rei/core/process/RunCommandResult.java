package dev.mikoto2000.rei.core.process;

/** runCommand の正規化済み結果。 */
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
  public RunCommandResult(String status,String executionMode,String resolvedExecutionMode,Integer exitCode,
      String stdout,String stderr,String processId,Long pid,boolean timedOut,String errorMessage) {
    this(status,executionMode,resolvedExecutionMode,exitCode,stdout,stderr,processId,pid,timedOut,errorMessage,null);
  }
  public RunCommandResult {
    diagnosis=BuildTestFailureDiagnosis.command(status,exitCode,timedOut,stdout,stderr,errorMessage);
  }
}
