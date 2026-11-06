package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握で受け渡す画面画像Persistence判断基準を表す。
 *
 * <p>Evidence is optional. Success and extraction failure are independent opt-ins.
 *
 * @param retentionDays 画像の保存日数
 * @param keepScreenshots 成功時に画像を保存するか
 * @param keepOnExtractionFailure 抽出失敗時に画像を保存するか
 */
public record ScreenshotPersistencePolicy(
    int retentionDays, boolean keepScreenshots, boolean keepOnExtractionFailure) {
  /** 現在の設定から画像保存に必要な値を取り出す。 */
  public ScreenshotPersistencePolicy(ActivityProperties properties) {
    this(
        properties.getScreenshotRetentionDays(),
        properties.isKeepScreenshots(),
        properties.isKeepOnExtractionFailure());
  }

  /** 作業状況の把握における最終結果の選択肢。 */
  public enum Outcome {
    /** 成功。 */
    SUCCESS,
    /** EXTRACTION失敗情報。 */
    EXTRACTION_FAILURE
  }

  /** 作業状況の把握において、保存の実施条件を判定する。 */
  public boolean shouldSave(boolean duplicate, Outcome outcome) {
    if (duplicate || retentionDays == 0) return false;
    return switch (outcome) {
      case SUCCESS -> keepScreenshots;
      case EXTRACTION_FAILURE -> keepOnExtractionFailure;
    };
  }
}
