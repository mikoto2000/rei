package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握で受け渡す画面画像Persistence判断基準を表す。
 *
 * <p>Evidence is optional. Success and extraction failure are independent opt-ins.
 *
 * @param properties この機能の動作設定
 */
public record ScreenshotPersistencePolicy(ActivityProperties properties) {
  /** 作業状況の把握における最終結果の選択肢。 */
  public enum Outcome {
    /** 成功。 */
    SUCCESS,
    /** EXTRACTION失敗情報。 */
    EXTRACTION_FAILURE
  }

  /** 作業状況の把握において、保存の実施条件を判定する。 */
  public boolean shouldSave(boolean duplicate, Outcome outcome) {
    if (duplicate || properties.getScreenshotRetentionDays() == 0) return false;
    return switch (outcome) {
      case SUCCESS -> properties.isKeepScreenshots();
      case EXTRACTION_FAILURE -> properties.isKeepOnExtractionFailure();
    };
  }
}
