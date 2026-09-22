package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;

/** Deterministic session projection. Midnight is a boundary in the configured journal timezone. */
public record SessionMergePolicy(Duration maximumGap, ZoneId zone) {
  public List<ActivitySession> aggregate(List<ActivityRecord> records) {
    var sessions = new ArrayList<ActivitySession>();
    ActivityRecord previous = null;
    for (var record : records.stream().sorted(Comparator.comparing(ActivityRecord::capturedAt)).toList()) {
      if(!sessions.isEmpty() && sessions.getLast().endedAt().isAfter(record.capturedAt())) {
        var old=sessions.removeLast();
        long overlap=Duration.between(record.capturedAt(),old.endedAt()).getSeconds();
        sessions.add(new ActivitySession(old.id(),old.startedAt(),record.capturedAt(),
            Math.max(0,old.observedSeconds()-overlap),old.recordIds(),old.inference(),old.primaryApplication(),old.confidence()));
      }
      var midnight = record.capturedAt().atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
      var end = record.capturedAt().plusSeconds(record.durationEstimate());
      if (end.isAfter(midnight)) end = midnight;
      String app = record.foreground() == null ? "unknown" : record.foreground().processName();
      boolean merge = previous != null && !sessions.isEmpty()
          && previous.capturedAt().atZone(zone).toLocalDate().equals(record.capturedAt().atZone(zone).toLocalDate())
          && Duration.between(previous.capturedAt(),record.capturedAt()).compareTo(maximumGap) <= 0
          && Objects.equals(previous.continuityId(),record.continuityId())
          && Objects.equals(previous.foreground(),record.foreground())
          && similar(previous.inference(),record.inference());
      if (merge) {
        var old = sessions.removeLast();
        var ids = new ArrayList<>(old.recordIds()); ids.add(record.id());
        long added = Math.max(0,Duration.between(record.capturedAt().isAfter(old.endedAt()) ? record.capturedAt() : old.endedAt(),end).getSeconds());
        sessions.add(new ActivitySession(old.id(),old.startedAt(),end.isAfter(old.endedAt()) ? end : old.endedAt(),
            old.observedSeconds()+added,ids,old.inference(),app,Math.min(old.confidence(),record.confidence())));
      } else sessions.add(new ActivitySession(record.id(),record.capturedAt(),end,
          Duration.between(record.capturedAt(),end).getSeconds(),List.of(record.id()),record.inference(),app,record.confidence()));
      previous = record;
    }
    return List.copyOf(sessions);
  }
  private static boolean similar(ActivityRecord.Inference a,ActivityRecord.Inference b) {
    if(a.activities().isEmpty() || b.activities().isEmpty())return a.equals(b);
    return new HashSet<>(a.activities()).equals(new HashSet<>(b.activities()));
  }
}
