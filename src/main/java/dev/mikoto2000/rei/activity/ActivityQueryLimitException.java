package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握を継続できない作業状況検索条件上限の状況を伝える例外。
 *
 * <p>No partial statistics are published after exceeding the query's evidence budget.
 */
public class ActivityQueryLimitException extends RuntimeException {
  /** 既定の状態でActivityQueryLimitExceptionを初期化する。 */
  public ActivityQueryLimitException() {
    super("Activity analysis evidence limit exceeded");
  }
}
