package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 作業状況バックグラウンド保存の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ActivityBackgroundStorageTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir java.nio.file.Path directory;

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void delayedSupplementUpdatesOneRecordWithoutAddingTimeOrChangingPrimary() {
    var ds = new org.sqlite.SQLiteDataSource();
    ds.setUrl("jdbc:sqlite:" + directory.resolve("test.db"));
    var store =
        new SqliteActivityStore(ds, new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC));
    var record = ActivityPolicyTest.record("2026-09-23T10:00:00Z", "coding");
    var later = ActivityPolicyTest.record("2026-09-23T10:01:00Z", "coding");
    store.append(record);
    store.append(later);
    var media = new ActivityRecord.Activity("m1", "media", "Chrome", "YouTube", "video", "");
    var conflicting = new ActivityRecord.Activity("m1", "social", "idea", "X", "", "rei");
    var supplement =
        new ActivityExtractor.Result(
            new ActivityRecord.Inference("background", List.of(media, conflicting)), .9);
    store.replace(ActivityBackgroundMerge.merge(record, supplement, .5));
    var records = store.findRecordsBetween(record.capturedAt(), later.capturedAt().plusSeconds(60));
    assertEquals(2, records.size());
    assertEquals(120, records.stream().mapToLong(ActivityRecord::durationEstimate).sum());
    assertFalse(records.getFirst().inference().activities().contains(media));
    var context = records.getFirst().detection().visionDiagnostics().background().context();
    assertEquals(List.of(media, conflicting), context.candidates());
    assertEquals(record.id(), context.observationId());
    assertEquals(record.capturedAt(), context.observedAt());
    assertFalse(records.getFirst().inference().activities().contains(conflicting));
    assertEquals(record.inference().summary(), records.getFirst().inference().summary());
    assertEquals(
        new ActivityRolePolicy().classify(record).primary(),
        new ActivityRolePolicy().classify(records.getFirst()).primary());
    assertEquals(
        120,
        store.findBetween(record.capturedAt(), later.capturedAt().plusSeconds(60)).stream()
            .mapToLong(ActivitySession::observedSeconds)
            .sum());
    assertThrows(
        IllegalStateException.class,
        () -> store.replace(ActivityPolicyTest.record("2026-09-23T11:00:00Z", "coding")));
    assertEquals(
        2,
        store.findRecordsBetween(record.capturedAt(), later.capturedAt().plusSeconds(60)).size());
  }
}
