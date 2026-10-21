package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 作業状況判断基準の結果と状態の契約を検証するテスト。 */
class ActivityPolicyTest {
  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void disabledByDefault() {
    assertFalse(new ActivityProperties().isEnabled());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void excludesProcessIgnoringCaseAndExtension() {
    var p = new ActivityProperties();
    assertTrue(new CapturePolicy(p).excluded(new ForegroundWindow("keepassxc", 1, "", "1")));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void excludesWildcardTitle() {
    assertTrue(
        new CapturePolicy(new ActivityProperties())
            .excluded(new ForegroundWindow("firefox", 1, "Enter Password now", "1")));
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unknownMetadataFailsClosed() {
    assertTrue(new CapturePolicy(new ActivityProperties()).excluded(null));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ordinaryWindowAllowed() {
    assertFalse(
        new CapturePolicy(new ActivityProperties())
            .excluded(new ForegroundWindow("idea", 1, "rei", "1")));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void identicalImage() {
    assertEquals(0, ImageChange.distance(image(20), image(20)), 0.0001);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void smallChangeIsSimilar() {
    assertTrue(ImageChange.distance(image(20), image(22)) < .03);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void majorChangeIsDifferent() {
    assertTrue(ImageChange.distance(image(20), image(200)) > .03);
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void nextActivityClipsPreviousEstimate() {
    var result =
        sessions(record("2026-09-22T10:00:00Z", "coding"), record("2026-09-22T10:00:30Z", "media"));
    assertEquals(Instant.parse("2026-09-22T10:00:30Z"), result.getFirst().endedAt());
    assertEquals(30, result.getFirst().observedSeconds());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sameActivityMerges() {
    assertEquals(
        1,
        sessions(record("2026-09-22T10:00:00Z", "coding"), record("2026-09-22T10:01:00Z", "coding"))
            .size());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void differentActivitySplits() {
    assertEquals(
        2,
        sessions(record("2026-09-22T10:00:00Z", "coding"), record("2026-09-22T10:01:00Z", "media"))
            .size());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void longGapSplits() {
    assertEquals(
        2,
        sessions(record("2026-09-22T10:00:00Z", "coding"), record("2026-09-22T10:10:00Z", "coding"))
            .size());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void midnightSplits() {
    assertEquals(
        2,
        sessions(record("2026-09-22T23:59:00Z", "coding"), record("2026-09-23T00:00:00Z", "coding"))
            .size());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void midnightDurationIsClipped() {
    assertEquals(
        Instant.parse("2026-09-23T00:00:00Z"),
        sessions(record("2026-09-22T23:59:30Z", "coding")).getFirst().endedAt());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void summaryWordingDoesNotSplitSameCandidates() {
    var a = record("2026-09-22T10:00:00Z", "coding");
    var b = record("2026-09-22T10:01:00Z", "coding");
    b =
        new ActivityRecord(
            b.id(),
            b.capturedAt(),
            60,
            b.observations(),
            b.foreground(),
            new ActivityRecord.Inference("別の言い回し", b.inference().activities()),
            .8,
            List.of(),
            .1,
            false);
    assertEquals(1, sessions(a, b).size());
  }

  /** {@code image} の条件を再現するための補助情報を返す。 */
  static BufferedImage image(int gray) {
    var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 32; y++)
      for (int x = 0; x < 32; x++) image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
    return image;
  }

  /** 検証用に、後から経緯を確認できるよう、指定された内容を記録する。 */
  static ActivityRecord record(String time, String type) {
    return new ActivityRecord(
        UUID.randomUUID().toString(),
        Instant.parse(time),
        60,
        List.of(
            new ActivityRecord.Observation(
                "m1", new ActivityRecord.Bounds(0, 0, 32, 32), Instant.parse(time))),
        new ForegroundWindow("idea", 1, "rei", "1"),
        new ActivityRecord.Inference(
            "visible " + type,
            List.of(new ActivityRecord.Activity("m1", type, "idea", "", "", "rei"))),
        .8,
        List.of(),
        .1,
        false);
  }

  /** {@code sessions} の条件を再現するための補助情報を返す。 */
  static List<ActivitySession> sessions(ActivityRecord... records) {
    return new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC)
        .aggregate(List.of(records));
  }
}
