package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 作業状況根拠表示表示形式への変換の結果と状態の契約を検証するテスト。 */
class ActivityEvidenceDisplayFormatterTest {
  /** 検証用に、後から経緯を確認できるよう、指定された内容を記録する。 */
  static ActivityRecord record(
      VisionDiagnostics.Result foreground, VisionDiagnostics.Result background) {
    var r = ActivitySemanticTest.dev(0, "Terminal", "X");
    var diagnostics =
        new ClassificationDiagnostics(
            List.of("dev-rule"),
            "dev-rule",
            "user",
            new ActivityFieldConfidence(.9, 1, .8, .7, 0),
            true,
            false,
            "CLASSIFICATION_USABLE",
            List.of(),
            EntertainmentDisposition.NON_ENTERTAINMENT,
            .9,
            "dev",
            "user",
            "rule");
    var d =
        new ActivityRecord.Detection(
            null,
            List.of("FOREGROUND_WINDOW", "WINDOW_TITLE"),
            true,
            "EVIDENCE_PLUS_VISION",
            "FINAL",
            Map.of(),
            "dev-rule",
            diagnostics.confidence(),
            List.of(),
            diagnostics,
            new VisionDiagnostics(foreground, background));
    return new ActivityRecord(
        r.id(),
        r.capturedAt(),
        r.durationEstimate(),
        r.observations(),
        r.foreground(),
        r.inference(),
        r.confidence(),
        List.of("private-image"),
        r.changeAmount(),
        false,
        r.continuityId(),
        d);
  }

  /** {@code state} の条件を再現するための補助情報を返す。 */
  static VisionDiagnostics.Result state(VisionDiagnostics.State state) {
    return new VisionDiagnostics.Result(state, null);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void windowOnly() {
    assertEquals(
        "Window",
        new ActivityEvidenceDisplayFormatter()
            .evidence(
                record(
                    state(VisionDiagnostics.State.NOT_ATTEMPTED),
                    state(VisionDiagnostics.State.NOT_ATTEMPTED))));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void foregroundAndBackgroundUsedAreIndependent() {
    var f = new ActivityEvidenceDisplayFormatter();
    var used = state(VisionDiagnostics.State.USED);
    var no = state(VisionDiagnostics.State.NOT_ATTEMPTED);
    assertEquals("Window + Foreground Vision", f.evidence(record(used, no)));
    assertEquals("Window + Background Vision", f.evidence(record(no, used)));
    assertEquals("Window + Foreground Vision + Background Vision", f.evidence(record(used, used)));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @ParameterizedTest
  @EnumSource(
      value = VisionDiagnostics.State.class,
      names = {"ATTEMPTED_FAILED", "ATTEMPTED_SUCCEEDED_NOT_USED", "ATTEMPTED", "UNKNOWN"})
  void unusedImagesNeverBecomeEvidence(VisionDiagnostics.State status) {
    var r =
        record(
            new VisionDiagnostics.Result(
                status,
                status == VisionDiagnostics.State.ATTEMPTED_FAILED
                    ? ActivityVisionFailure.OUTPUT_LIMIT
                    : null),
            state(VisionDiagnostics.State.NOT_ATTEMPTED));
    var f = new ActivityEvidenceDisplayFormatter();
    assertEquals("Window", f.evidence(r));
    assertTrue(f.verbose(r).contains(status.name()));
    if (status == VisionDiagnostics.State.ATTEMPTED_FAILED)
      assertTrue(f.verbose(r).contains("OUTPUT_LIMIT"));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void verboseShowsSavedDiagnosticsWithoutRawData() {
    var text =
        new ActivityEvidenceDisplayFormatter()
            .verbose(
                record(
                    state(VisionDiagnostics.State.USED),
                    state(VisionDiagnostics.State.NOT_ATTEMPTED)));
    for (var value :
        List.of(
            "confidence:",
            "category=0.9",
            "service=0.8",
            "application=1.0",
            "dev-rule",
            "CLASSIFICATION_USABLE",
            "usable: true",
            "EVIDENCE_PLUS_VISION")) assertTrue(text.contains(value), text);
    assertFalse(text.contains("private-image"));
    assertFalse(text.contains("積極的"));
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void legacyRecordDoesNotImplyVisionUsage() {
    var f = new ActivityEvidenceDisplayFormatter();
    var r = ActivitySemanticTest.dev(0, "Terminal", "X");
    assertEquals("Unknown", f.evidence(r));
    assertTrue(f.verbose(r).contains("UNKNOWN"));
  }
}
