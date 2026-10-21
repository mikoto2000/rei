package dev.mikoto2000.rei.activity;

import java.util.EnumSet;
import java.util.Set;

/**
 * 作業状況根拠表示を利用者が確認できる表示形式へ整える。
 *
 * <p>Display saved diagnostics only. No image, prompt, response, or window-title rendering.
 */
public final class ActivityEvidenceDisplayFormatter {
  /** 作業状況の把握における取得元の選択肢。 */
  public enum Source {
    /** 範囲付随情報。 */
    WINDOW_METADATA,
    /** 前面画像認識。 */
    FOREGROUND_VISION,
    /** バックグラウンド画像認識。 */
    BACKGROUND_VISION
  }

  /** 作業状況の把握で用いる取得元一覧を返す。 */
  public Set<Source> sources(ActivityRecord r) {
    var result = EnumSet.noneOf(Source.class);
    var d = r.detection();
    if (d == null) return result;
    if (d.classificationSources().stream()
        .anyMatch(
            s ->
                Set.of("FOREGROUND_WINDOW", "WINDOW_TITLE", "VISIBLE_WINDOWS", "WINDOW_METADATA")
                    .contains(s))) result.add(Source.WINDOW_METADATA);
    var vision = VisionDiagnostics.of(d);
    if (vision.foreground() != null && vision.foreground().state() == VisionDiagnostics.State.USED)
      result.add(Source.FOREGROUND_VISION);
    if (vision.background() != null && vision.background().state() == VisionDiagnostics.State.USED)
      result.add(Source.BACKGROUND_VISION);
    return result;
  }

  /** 作業状況の把握で用いる根拠を返す。 */
  public String evidence(ActivityRecord r) {
    var sources = sources(r);
    var label =
        sources.isEmpty()
            ? "Unknown"
            : String.join(
                " + ",
                sources.stream()
                    .map(
                        s ->
                            switch (s) {
                              case WINDOW_METADATA -> "Window";
                              case FOREGROUND_VISION -> "Foreground Vision";
                              case BACKGROUND_VISION -> "Background Vision";
                            })
                    .toList());
    var d = r.detection();
    var foreground = VisionDiagnostics.of(d).foreground();
    if (d != null
        && d.classificationSources().contains("HISTORY")
        && foreground != null
        && foreground.timing() != null
        && !r.id().equals(foreground.timing().observationId())) label += " + Historical Inference";
    return label;
  }

  /** 作業状況の把握で用いる詳細な形式を返す。 */
  public String verbose(ActivityRecord r) {
    var out = new StringBuilder("    confidence: " + r.confidence() + "\n");
    var d = r.detection();
    if (d != null) {
      out.append("    mode: ")
          .append(clean(d.classificationMode()))
          .append("; status: ")
          .append(clean(d.status()))
          .append('\n');
      out.append("    evidence sources (saved): ")
          .append(clean(d.classificationSources().toString()))
          .append('\n');
      if (d.fieldConfidence() != null) {
        var c = d.fieldConfidence();
        out.append("    confidence: category=")
            .append(c.category())
            .append(" application=")
            .append(c.application())
            .append(" service=")
            .append(c.service())
            .append(" project=")
            .append(c.project())
            .append(" content=")
            .append(c.content())
            .append('\n');
      }
      if (!r.inference().activities().isEmpty()) {
        var a = r.inference().activities().getFirst();
        out.append("    category: ")
            .append(clean(a.type()))
            .append("; service: ")
            .append(clean(a.service()))
            .append("; application: ")
            .append(clean(a.application()))
            .append('\n');
      }
      var diagnostics = d.diagnostics();
      if (diagnostics != null) {
        out.append("    usable: ").append(diagnostics.classificationUsable()).append('\n');
        out.append("    winning rule: ")
            .append(clean(diagnostics.winningRuleId()))
            .append("; matched: ")
            .append(clean(String.valueOf(diagnostics.matchedRuleIds())))
            .append('\n');
        out.append("    vision required: ")
            .append(diagnostics.visionRequired())
            .append("; reason: ")
            .append(clean(diagnostics.visionRequiredReason()))
            .append('\n');
        out.append("    unknown reasons: ")
            .append(clean(String.valueOf(diagnostics.unknownReasons())))
            .append('\n');
      }
      out.append("    classification reason: ").append(clean(d.reason())).append('\n');
    }
    var v = VisionDiagnostics.of(d);
    vision(out, "Foreground Vision", v.foreground());
    vision(out, "Background Vision", v.background());
    var context = v.background() == null ? null : v.background().context();
    if (context != null) {
      out.append("    補助画面の推定（時間加算なし）: observationId=")
          .append(code(context.observationId()))
          .append("; observedAt=")
          .append(context.observedAt())
          .append("; confidence=")
          .append(context.confidence())
          .append('\n');
      for (var candidate : context.candidates())
        out.append("      monitor=")
            .append(code(candidate.monitor()))
            .append("; candidate=")
            .append(
                code(
                    candidate.type()
                        + " / "
                        + candidate.application()
                        + " / "
                        + candidate.service()
                        + " / "
                        + candidate.contentTitle()))
            .append('\n');
    }
    return out.toString();
  }

  /** 作業状況の把握で用いるコードを返す。 */
  private static String code(String value) {
    return "`" + TemporalActivityEvidence.clean(value).replace("`", "'") + "`";
  }

  /** 作業状況の把握に関する画像認識を処理する。 */
  private static void vision(StringBuilder out, String label, VisionDiagnostics.Result result) {
    out.append("    ")
        .append(label)
        .append(": ")
        .append(result == null ? VisionDiagnostics.State.UNKNOWN : result.state());
    if (result != null && result.failure() != null)
      out.append("; reason: ").append(result.failure());
    out.append('\n');
  }

  /** 作業状況の把握で用いる変更なしを返す。 */
  static String clean(String value) {
    if (value == null) return "Unknown";
    var text = value.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]+", " ").strip();
    return text.substring(0, Math.min(240, text.length()));
  }
}
