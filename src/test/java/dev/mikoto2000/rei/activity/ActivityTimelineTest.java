package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 作業状況時系列の記録の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ActivityTimelineTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う保存先。 */
  SqliteActivityStore store;

  /** 検証に使う時系列の記録。 */
  ActivityTimeline timeline;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var ds = new org.sqlite.SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("activity.db"));
    store =
        new SqliteActivityStore(ds, new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC));
    timeline =
        new ActivityTimeline(
            store, Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC));
    store.append(ActivityPolicyTest.record("2026-09-22T10:00:00Z", "coding"));
    store.append(ActivityPolicyTest.record("2026-09-22T10:01:00Z", "coding"));
    store.append(ActivityPolicyTest.record("2026-09-23T08:00:00Z", "research"));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void today() {
    assertEquals("visible research", timeline.query("today").getFirst().inference().summary());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void yesterday() {
    assertEquals(2, timeline.query("yesterday").getFirst().recordIds().size());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void specifiedDate() {
    assertEquals(1, timeline.findByDate(LocalDate.parse("2026-09-22")).size());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void rangeClipsSession() {
    var s =
        timeline
            .findBetween(
                Instant.parse("2026-09-22T10:00:30Z"), Instant.parse("2026-09-22T10:01:30Z"))
            .getFirst();
    assertEquals(60, Duration.between(s.startedAt(), s.endedAt()).getSeconds());
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void rangeIsHalfOpen() {
    assertTrue(
        timeline
            .findBetween(
                Instant.parse("2026-09-22T10:02:00Z"), Instant.parse("2026-09-22T11:00:00Z"))
            .isEmpty());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidRangeRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> timeline.findBetween(Instant.MAX, Instant.MIN));
  }

  /** 作業状況の把握で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void dataSurvivesReopen() {
    var ds = new org.sqlite.SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("activity.db"));
    var reopened =
        new SqliteActivityStore(ds, new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC));
    assertEquals(
        2,
        reopened
            .findBetween(
                Instant.parse("2026-09-22T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"))
            .size());
  }

  /** 画面画像保持期間について、記録一覧を維持することを確認する。 */
  @Test
  void screenshotRetentionKeepsRecordsAndSessions() throws Exception {
    var screenshots = new FileScreenshotStore(directory.resolve("screenshots"));
    var old =
        screenshots.save(
            "00000000-0000-0000-0000-000000000001",
            Instant.parse("2026-09-19T10:00:00Z"),
            ActivityCaptureTest.screen(20));
    var recent =
        screenshots.save(
            "00000000-0000-0000-0000-000000000002",
            Instant.parse("2026-09-23T10:00:00Z"),
            ActivityCaptureTest.screen(20));
    screenshots.cleanup(Instant.parse("2026-09-20T10:00:00Z"));
    assertFalse(Files.exists(directory.resolve("screenshots").resolve(old.getFirst())));
    assertTrue(Files.exists(directory.resolve("screenshots").resolve(recent.getFirst())));
    assertEquals(2, timeline.query("yesterday").getFirst().recordIds().size());
  }

  /** 要約について、意味による表示用の状態を利用することを確認する。 */
  @Test
  void summaryUsesSemanticProjection() {
    String summary = timeline.summary("yesterday");
    assertTrue(summary.contains("rei関連の開発"));
    assertFalse(summary.contains("visible coding"));
    assertTrue(summary.contains("10:00"));
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void daylightSavingDateHasCorrectBounds() {
    var mock = org.mockito.Mockito.mock(ActivityStore.class);
    var t =
        new ActivityTimeline(
            mock,
            Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"), ZoneId.of("America/New_York")));
    t.findByDate(LocalDate.parse("2026-03-08"));
    org.mockito.Mockito.verify(mock)
        .findBetween(Instant.parse("2026-03-08T05:00:00Z"), Instant.parse("2026-03-09T04:00:00Z"));
  }
}
