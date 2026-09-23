package dev.mikoto2000.rei.activity;
import java.time.Instant;
import java.util.List;
public interface ActivityStore {
  void append(ActivityRecord record);
  /** Replace an existing observation and rebuild its session projection without adding observed time. */
  void replace(ActivityRecord record);
  List<ActivitySession> findBetween(Instant start, Instant end);
  List<ActivityRecord> findRecordsBetween(Instant start, Instant end);
}
