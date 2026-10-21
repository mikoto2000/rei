package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握で受け渡す作業状況各項目の確信度を表す。
 *
 * <p>Independent rule/model confidence, not calibrated probabilities. Missing fields have zero
 * confidence.
 *
 * @param category 分類
 * @param application アプリケーション
 * @param service サービス
 * @param project プロジェクト
 * @param content 内容
 */
public record ActivityFieldConfidence(
    double category, double application, double service, double project, double content) {
  /** 受け取った情報からActivityFieldConfidenceを作成する。 */
  public ActivityFieldConfidence {
    for (double value : new double[] {category, application, service, project, content})
      if (!Double.isFinite(value) || value < 0 || value > 1)
        throw new IllegalArgumentException("Invalid field confidence");
  }

  /** 作業状況の把握で用いるoverallを返す。 */
  public double overall() {
    return Math.min(category, Math.max(application, service));
  }

  /** 作業状況の把握において、利用可能に該当するか判定する。 */
  public boolean usable(double categoryThreshold) {
    return category > 0 && category >= categoryThreshold && Math.max(application, service) >= .8;
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  public boolean complete() {
    return category > 0 && application > 0 && service > 0 && project > 0 && content > 0;
  }

  /** 作業状況の把握において、部分的な結果に該当するか判定する。 */
  public boolean partial() {
    return !complete() && Math.max(application, service) > 0;
  }

  /** 作業状況の把握で利用する情報を、受け取った形式から変換する。 */
  public static ActivityFieldConfidence from(ActivityRecord.Activity a, double confidence) {
    return new ActivityFieldConfidence(
        ActivityVocabulary.category(a.type()).equals("unknown") ? 0 : confidence,
        known(a.application(), confidence),
        known(a.service(), confidence),
        known(a.projectCandidate(), confidence),
        known(a.contentTitle(), confidence));
  }

  /** 作業状況の把握で用いる補助的なを返す。 */
  public ActivityFieldConfidence secondary() {
    return new ActivityFieldConfidence(
        Math.min(category, .75),
        application,
        Math.min(service, .8),
        Math.min(project, .75),
        Math.min(content, .75));
  }

  /** 作業状況の把握で用いる既知を返す。 */
  private static double known(String value, double confidence) {
    return value == null || value.isBlank() ? 0 : confidence;
  }
}
