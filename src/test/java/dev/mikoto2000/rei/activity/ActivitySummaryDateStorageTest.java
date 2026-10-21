package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 作業状況要約日付保存の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ActivitySummaryDateStorageTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証用に、後から経緯を確認できるよう、指定された内容を記録する。 */
  ActivityRecord record(String instant, String project) {
    return new ActivityRecord(
        project + instant,
        Instant.parse(instant),
        60,
        List.of(),
        new ForegroundWindow("Terminal", 1, project, "terminal"),
        new ActivityRecord.Inference(
            "", List.of(ActivitySemanticTest.activity("coding", "Terminal", "", project))),
        .9,
        List.of(),
        .1,
        false);
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void summariesSeparateMidnightAndExcludeFutureEvidenceWithoutRewritingData() {
    var ds = new org.sqlite.SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("activity.db"));
    var store =
        new SqliteActivityStore(
            ds, new SessionMergePolicy(Duration.ofSeconds(90), ActivitySummaryDateTest.TOKYO));
    var records =
        List.of(
            record("2026-09-24T14:58:00Z", "previous-project"),
            record("2026-09-24T14:59:59.500Z", "previous-project"),
            record("2026-09-24T15:00:00Z", "today-project"),
            record("2026-09-25T02:59:30Z", "ongoing-project"),
            record("2026-09-25T03:00:00Z", "at-now-project"),
            record("2026-09-25T03:01:00Z", "future-project"));
    records.forEach(store::append);
    var timeline =
        new ActivityTimeline(
            store, Clock.fixed(ActivitySummaryDateTest.NOW, ActivitySummaryDateTest.TOKYO));
    var fine = timeline.query("today");
    assertEquals(
        records.subList(0, 2),
        timeline.summarySegments("yesterday").stream()
            .flatMap(s -> s.evidence().stream())
            .toList());
    assertEquals(
        records.subList(2, records.size()),
        timeline.summarySegments("today").stream().flatMap(s -> s.evidence().stream()).toList());
    var yesterday = timeline.trendSummary("yesterday");
    var today = timeline.trendSummary("today");
    assertTrue(yesterday.contains("previous-project"));
    assertFalse(yesterday.contains("today-project"));
    assertTrue(today.startsWith("Activity Summary — 2026-09-25"));
    assertTrue(today.contains("today-project"));
    assertTrue(today.contains("画面観測データあり: 0時間1分30秒"));
    assertFalse(today.contains("previous-project"));
    assertFalse(today.contains("at-now-project"));
    assertFalse(today.contains("future-project"));
    assertEquals(today, timeline.trendSummary("2026-09-25"));
    assertEquals(yesterday, timeline.trendSummary("2026-09-24"));
    assertEquals(fine, timeline.query("today"));
    assertEquals(
        records,
        store.findRecordsBetween(
            Instant.parse("2026-09-23T15:00:00Z"), Instant.parse("2026-09-25T15:00:00Z")));
    assertEquals(
        timeline.summary("yesterday"), new ActivityTools(timeline).activitySummary("yesterday"));
  }
}
