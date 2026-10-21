package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.dependency.DependencyAwaiter;
import dev.mikoto2000.rei.core.dependency.DependencyObservation;
import dev.mikoto2000.rei.core.dependency.DependencyState;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.goal.FileGoalVerifier;
import dev.mikoto2000.rei.goal.GoalRepository;
import java.time.Duration;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * ファイル依存条件をモデルから呼び出せるツールとして提供する。
 *
 * <p>Read-only file adapter sharing independent Goal verification and bounded waiting.
 */
@Component
public class FileDependencyTools {
  /** 確認の窓口。 */
  private final FileGoalVerifier verifier;

  /** 依存条件が満たされるまでの待機を管理する窓口。 */
  private DependencyAwaiter awaiter =
      new DependencyAwaiter(
          dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(), Thread::sleep);

  /** 指定された値や利用先を使って、FileDependencyToolsを初期化する。 */
  public FileDependencyTools(FileGoalVerifier verifier) {
    this.verifier = verifier;
  }

  /** 共通の実行基盤で使用する依存条件Awaiterを設定する。 */
  void setDependencyAwaiter(DependencyAwaiter awaiter) {
    this.awaiter = awaiter;
  }

  /** 必要な状態が整ってから先へ進むため、対象ファイルを待つ。 */
  @Tool(
      name = "waitForFile",
      description =
          "Wait up to 60 seconds (default 10) for a regular file inside the captured Project. Optional expectedSha256 requires exact content digest; absent digest requires existence. File size limit 1 MiB; symlinks/traversal rejected. WAITING at timeout is not completion. Reads no file contents into the model and never writes.")
  public DependencyObservation waitForFile(
      String relativeFile,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String expectedSha256,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer timeoutSeconds,
      ToolContext toolContext) {
    var owner = AgentRunScope.current();
    if (owner == null || owner.projectId() == null || owner.projectId().isBlank())
      throw new IllegalArgumentException("Owning project required");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (relativeFile == null || relativeFile.isBlank() || relativeFile.length() > 1024)
      throw new IllegalArgumentException("Relative file is required (up to 1024 characters)");
    if (expectedSha256 != null && !expectedSha256.matches("[a-fA-F0-9]{64}"))
      throw new IllegalArgumentException("Expected SHA-256 must have 64 hexadecimal characters");
    var criterion =
        new GoalRepository.FileCriterion(
            relativeFile,
            expectedSha256 == null ? null : expectedSha256.toLowerCase(java.util.Locale.ROOT));
    var execution =
        toolContext != null
                && toolContext.getContext().get(RunExecutionContext.KEY)
                    instanceof RunExecutionContext current
            ? current
            : null;
    var observation =
        awaiter.await(
            () -> {
              var checked = verifier.verify(owner.projectRoot(), criterion);
              var state =
                  checked.satisfied()
                      ? DependencyState.COMPLETED
                      : java.util.Set.of("file_missing_or_not_regular", "digest_mismatch")
                              .contains(checked.reason())
                          ? DependencyState.WAITING
                          : DependencyState.BLOCKED;
              return new DependencyObservation("file:" + relativeFile, state, checked.reason());
            },
            Duration.ofSeconds(timeoutSeconds == null ? 10 : timeoutSeconds),
            () -> {
              if (execution != null) execution.checkActive();
            });
    if (execution != null) execution.observeDependency(observation);
    return observation;
  }
}
