package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

public record ActivitySession(String id, Instant startedAt, Instant endedAt, long observedSeconds,
    List<String> recordIds, ActivityRecord.Inference inference, String primaryApplication, double confidence) {
  public ActivitySession { recordIds = List.copyOf(recordIds); }
}
