package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/** OS/app facts, separate from inferred activities. No image bytes or tool arguments. */
public record ActivityEvidence(Instant capturedAt,ForegroundWindow foreground,List<VisibleWindow> visibleWindows,
    String projectName,String projectId,List<RecentEvent> events,History history,WorkReference workContext) {
  public ActivityEvidence {visibleWindows=List.copyOf(visibleWindows);events=List.copyOf(events);}
  public ActivityEvidence(Instant at,ForegroundWindow foreground,List<VisibleWindow> windows,String projectName,
      String projectId,List<RecentEvent> events,History history){this(at,foreground,windows,projectName,projectId,events,history,null);}
  /** Immutable observation-time references; task association never proves user engagement or task success. */
  public record WorkReference(String projectId,Instant capturedAt,long revision,Instant contextUpdatedAt,
      GitReference git,List<ItemReference> items,boolean partial) {
    public WorkReference {items=List.copyOf(items);}
  }
  public record GitReference(String branch,String commit,Instant capturedAt) {}
  public record ItemReference(String id,String kind,String status,String certainty,List<SourceReference> sources) {
    public ItemReference {sources=List.copyOf(sources);}
  }
  public record SourceReference(String eventId,String sessionId,String turnId,String runId,String toolCallId,
      String file,Instant observedAt) {}
  public record VisibleWindow(ForegroundWindow window,boolean visible,boolean minimized,boolean offScreen,String monitor) {}
  public record RecentEvent(Instant at,String projectId,String kind,String eventId,String sessionId,String turnId,String runId) {
    public RecentEvent(Instant at,String projectId,String kind){this(at,projectId,kind,null,null,null,null);}
  }
  public record History(Instant at,ForegroundWindow foreground,ActivityRecord.Inference inference,double confidence) {}
}
