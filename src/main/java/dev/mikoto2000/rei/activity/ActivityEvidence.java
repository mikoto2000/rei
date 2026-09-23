package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/** OS/app facts, separate from inferred activities. No image bytes or tool arguments. */
public record ActivityEvidence(Instant capturedAt,ForegroundWindow foreground,List<VisibleWindow> visibleWindows,
    String projectName,String projectId,List<RecentEvent> events,History history) {
  public ActivityEvidence {visibleWindows=List.copyOf(visibleWindows);events=List.copyOf(events);}
  public record VisibleWindow(ForegroundWindow window,boolean visible,boolean minimized,boolean offScreen,String monitor) {}
  public record RecentEvent(Instant at,String projectId,String kind) {}
  public record History(Instant at,ForegroundWindow foreground,ActivityRecord.Inference inference,double confidence) {}
}
