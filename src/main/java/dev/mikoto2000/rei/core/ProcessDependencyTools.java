package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.dependency.DependencyAwaiter;
import dev.mikoto2000.rei.core.dependency.DependencyObservation;
import dev.mikoto2000.rei.core.dependency.DependencyState;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.core.process.BackgroundProcessSnapshot;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * プロセス依存条件をモデルから呼び出せるツールとして提供する。
 *
 * <p>Managed-process adapter; waiting policy and time are kept in the domain awaiter.
 */
@Component
public class ProcessDependencyTools {
  /** プロセス一覧。 */
  private final BackgroundProcessManager processes;

  /** 依存条件が満たされるまでの待機を管理する窓口。 */
  private DependencyAwaiter awaiter =
      new DependencyAwaiter(
          dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(), Thread::sleep);

  /** 指定された値や利用先を使って、ProcessDependencyToolsを初期化する。 */
  public ProcessDependencyTools(BackgroundProcessManager processes) {
    this.processes = processes;
  }

  /** 共通の実行基盤で使用する依存条件Awaiterを設定する。 */
  void setDependencyAwaiter(DependencyAwaiter awaiter) {
    this.awaiter = awaiter;
  }

  /**
   * 共通の実行基盤で受け渡すシェル依存条件結果を表す。
   *
   * @param dependency 依存条件
   * @param process プロセス
   */
  public record ShellDependencyResult(
      DependencyObservation dependency, BackgroundProcessSnapshot process) {}

  /** 必要な状態が整ってから先へ進むため、対象シェルプロセスを待つ。 */
  @Tool(
      name = "waitForShellProcess",
      description =
          "Wait 0 to 60 seconds for an existing managed shell process without launching or killing it. Default 10 seconds; 0 checks immediately. Returns COMPLETED, FAILED, CANCELLED, BLOCKED (unknown process), or WAITING at timeout. WAITING is not successful completion; wait again with the same processId instead of relaunching the command. Uses bounded polling instead of repeated LLM status requests.")
  public ShellDependencyResult waitForShellProcess(
      String processId,
      @ToolParam(
              required = false,
              description =
                  "Integer seconds from 0 to 60 inclusive. Omit or use null for the default 10 seconds; 0 checks immediately.")
          Integer timeoutSeconds,
      ToolContext toolContext) {
    int seconds = timeoutSeconds == null ? 10 : timeoutSeconds;
    var execution =
        toolContext != null
                && toolContext.getContext().get(RunExecutionContext.KEY)
                    instanceof RunExecutionContext current
            ? current
            : null;
    var latest = new AtomicReference<BackgroundProcessSnapshot>();
    var result =
        awaiter.await(
            () -> {
              var snapshot = processes.status(processId, null);
              latest.set(snapshot);
              var state =
                  !snapshot.found()
                      ? DependencyState.BLOCKED
                      : switch (snapshot.status()) {
                        case STARTING, RUNNING -> DependencyState.RUNNING;
                        case EXITED ->
                            snapshot.exitCode() != null && snapshot.exitCode() == 0
                                ? DependencyState.COMPLETED
                                : DependencyState.FAILED;
                        case FAILED -> DependencyState.FAILED;
                        case KILLED -> DependencyState.CANCELLED;
                      };
              return new DependencyObservation(processId, state, "managed_shell_process");
            },
            Duration.ofSeconds(seconds),
            () -> {
              if (execution != null) execution.checkActive();
            });
    if (execution != null) execution.observeDependency(result);
    return new ShellDependencyResult(result, latest.get());
  }
}
