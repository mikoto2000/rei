package dev.mikoto2000.rei.activity;

import java.util.List;
import java.util.Map;

/**
 * 作業状況の把握で受け渡す作業状況分類を表す。
 *
 * @param inference 推定
 * @param confidence 確信度
 * @param sources 取得元一覧
 * @param sourceConfidence 取得元ごとの確信度
 * @param reason 理由
 * @param fieldConfidence 各項目の確信度
 * @param secondaryConfidence 補助分類ごとの確信度
 */
public record ActivityClassification(
    ActivityRecord.Inference inference,
    double confidence,
    List<String> sources,
    Map<String, Double> sourceConfidence,
    String reason,
    ActivityFieldConfidence fieldConfidence,
    List<Secondary> secondaryConfidence) {
  /**
   * 作業状況の把握で受け渡す補助的なを表す。
   *
   * @param activity 作業状況
   * @param confidence 確信度
   */
  public record Secondary(ActivityRecord.Activity activity, ActivityFieldConfidence confidence) {}

  /** 受け取った情報からActivityClassificationを作成する。 */
  public ActivityClassification {
    sources = List.copyOf(sources);
    sourceConfidence = Map.copyOf(sourceConfidence);
    secondaryConfidence = List.copyOf(secondaryConfidence);
  }

  /** 作業状況の把握を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ActivityClassification(
      ActivityRecord.Inference inference,
      double confidence,
      List<String> sources,
      Map<String, Double> sourceConfidence,
      String reason) {
    this(
        inference,
        confidence,
        sources,
        sourceConfidence,
        reason,
        inference.activities().isEmpty()
            ? new ActivityFieldConfidence(0, 0, 0, 0, 0)
            : ActivityFieldConfidence.from(inference.activities().getFirst(), confidence),
        List.of());
  }

  /** 作業状況の把握において、利用可能に該当するか判定する。 */
  public boolean usable(double threshold) {
    return fieldConfidence.usable(threshold);
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  public boolean complete() {
    return fieldConfidence.complete();
  }

  /** 作業状況の把握において、部分的な結果に該当するか判定する。 */
  public boolean partial() {
    return fieldConfidence.partial();
  }
}
