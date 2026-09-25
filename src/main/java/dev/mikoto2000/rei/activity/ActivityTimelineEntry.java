package dev.mikoto2000.rei.activity;

import java.time.Instant;
import dev.mikoto2000.rei.activity.behavior.BehaviorTimelineEvent;

/** Presentation variants retain the distinction between observation intervals and notification instants. */
public sealed interface ActivityTimelineEntry {
  Instant timestamp();
  record ActivityEntry(SummarySegment segment) implements ActivityTimelineEntry {
    public Instant timestamp(){return segment.startedAt();}
  }
  record BehaviorEntry(BehaviorTimelineEvent event) implements ActivityTimelineEntry {
    public Instant timestamp(){return event.timestamp();}
  }
}
