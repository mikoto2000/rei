package dev.mikoto2000.rei.activity;

/**
 * 作業状況の把握に必要な作業状況バックグラウンド統合を扱う。
 *
 * <p>Desktop candidates are supplemental inference; they never enter the primary activity candidate
 * list.
 */
final class ActivityBackgroundMerge {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ActivityBackgroundMerge() {}

  /** 関連する情報をまとめて扱えるよう、作業状況バックグラウンド統合を統合する。 */
  static ActivityRecord merge(
      ActivityRecord r, ActivityExtractor.Result background, double threshold) {
    if (background.confidence() < threshold || background.inference().activities().isEmpty())
      return r;
    var d = r.detection();
    if (d == null)
      d =
          new ActivityRecord.Detection(
              null, java.util.List.of(), false, "LEGACY", "FINAL", java.util.Map.of(), "");
    var context =
        new VisionDiagnostics.Context(
            r.id(),
            r.capturedAt(),
            background.inference().activities().stream().limit(3).toList(),
            Math.min(.7, background.confidence()));
    var vision = VisionDiagnostics.of(d).context(context);
    var detection =
        new ActivityRecord.Detection(
            d.evidence(),
            d.classificationSources(),
            d.visionUsed(),
            d.classificationMode(),
            d.status(),
            d.sourceConfidence(),
            d.reason(),
            d.fieldConfidence(),
            d.secondaryConfidence(),
            d.diagnostics(),
            vision);
    return new ActivityRecord(
        r.id(),
        r.capturedAt(),
        r.durationEstimate(),
        r.observations(),
        r.foreground(),
        r.inference(),
        r.confidence(),
        r.screenshotReferences(),
        r.changeAmount(),
        r.duplicate(),
        r.continuityId(),
        detection);
  }
}
