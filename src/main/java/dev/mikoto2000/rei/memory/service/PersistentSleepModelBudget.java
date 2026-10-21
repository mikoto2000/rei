package dev.mikoto2000.rei.memory.service;

import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;

/**
 * 保存される待機モデルの使用量と上限を管理する。
 *
 * <p>Project lifetime accounting composed with the existing invocation budget.
 */
final class PersistentSleepModelBudget implements ModelCallBudget {
  /** モデル呼び出しの回数または予算。 */
  private final SleepModelBudget invocation;

  /** 保存と検索の窓口。 */
  private final MemoryRepository repository;

  /** この機能の動作設定。 */
  private final MemoryProperties.Sleep properties;

  /** プロジェクト。 */
  private final String project;

  /** 指定された値や利用先を使って、PersistentSleepModelBudgetを初期化する。 */
  PersistentSleepModelBudget(
      MemoryRepository repository,
      MemoryProperties.Sleep properties,
      String project,
      Runnable check) {
    this.repository = repository;
    this.properties = properties;
    this.project = project;
    invocation = new SleepModelBudget(properties, check);
  }

  /** 長期記憶の保存と検索の操作を実行する。 */
  public void run() {
    invocation.run();
    repository.reserveSleepModelCall(
        project, properties.maxLlmCallsPerProject(), properties.maxTotalTokensPerProject());
  }

  /** トークン数による実行予算の制限が有効かどうかを返す。 */
  public boolean tokenLimitEnabled() {
    return invocation.tokenLimitEnabled() || properties.maxTotalTokensPerProject() > 0;
  }

  /** 使用したトークン数を実行予算へ記録する。 */
  public void recordTotalTokens(Integer tokens) {
    if (properties.maxTotalTokensPerProject() > 0) {
      repository.recordSleepTokens(project, tokens);
      if (tokens == null || tokens <= 0) throw new ExecutionStoppedException(TOKEN_USAGE_UNKNOWN);
      repository.checkSleepTokenBudget(project, properties.maxTotalTokensPerProject());
    }
    invocation.recordTotalTokens(tokens);
  }
}
