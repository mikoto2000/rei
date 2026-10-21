package dev.mikoto2000.rei.feed;

/**
 * フィードの購読と収集を継続できない重複の有無フィードの状況を伝える例外。
 *
 * <p>Allows callers to distinguish duplicates from other registration failures.
 */
public class DuplicateFeedException extends IllegalArgumentException {
  /** 既定の状態でDuplicateFeedExceptionを初期化する。 */
  public DuplicateFeedException() {
    super("同じフィード URL は登録できません");
  }
}
