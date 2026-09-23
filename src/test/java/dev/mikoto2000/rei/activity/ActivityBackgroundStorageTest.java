package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ActivityBackgroundStorageTest {
  @TempDir java.nio.file.Path directory;
  @Test void delayedSupplementUpdatesOneRecordWithoutAddingTimeOrChangingPrimary() {
    var ds=new org.sqlite.SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+directory.resolve("test.db"));
    var store=new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC));
    var record=ActivityPolicyTest.record("2026-09-23T10:00:00Z","coding");
    var later=ActivityPolicyTest.record("2026-09-23T10:01:00Z","coding");store.append(record);store.append(later);
    var media=new ActivityRecord.Activity("m1","media","Chrome","YouTube","video","");
    var conflicting=new ActivityRecord.Activity("m1","social","idea","X","","rei");
    var supplement=new ActivityExtractor.Result(new ActivityRecord.Inference("background",List.of(media,conflicting)),.9);
    store.replace(ActivityBackgroundMerge.merge(record,supplement,.5));
    var records=store.findRecordsBetween(record.capturedAt(),later.capturedAt().plusSeconds(60));
    assertEquals(2,records.size());assertEquals(120,records.stream().mapToLong(ActivityRecord::durationEstimate).sum());
    assertTrue(records.getFirst().inference().activities().contains(media));
    assertFalse(records.getFirst().inference().activities().contains(conflicting));
    assertEquals(record.inference().summary(),records.getFirst().inference().summary());
    assertEquals(new ActivityRolePolicy().classify(record).primary(),new ActivityRolePolicy().classify(records.getFirst()).primary());
    assertEquals(120,store.findBetween(record.capturedAt(),later.capturedAt().plusSeconds(60)).stream().mapToLong(ActivitySession::observedSeconds).sum());
    assertThrows(IllegalStateException.class,()->store.replace(ActivityPolicyTest.record("2026-09-23T11:00:00Z","coding")));
    assertEquals(2,store.findRecordsBetween(record.capturedAt(),later.capturedAt().plusSeconds(60)).size());
  }
}
