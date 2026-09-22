package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.List;

/** Long-lived evidence and explicitly uncertain inference; screenshot references may expire independently. */
public record ActivityRecord(String id, Instant capturedAt, long durationEstimate,
    List<Observation> observations, ForegroundWindow foreground, Inference inference, double confidence,
    List<String> screenshotReferences, double changeAmount, boolean duplicate, String continuityId) {
  public ActivityRecord(String id, Instant capturedAt, long durationEstimate, List<Observation> observations,
      ForegroundWindow foreground, Inference inference, double confidence, List<String> screenshotReferences,
      double changeAmount, boolean duplicate) {
    this(id,capturedAt,durationEstimate,observations,foreground,inference,confidence,screenshotReferences,changeAmount,duplicate,"legacy");
  }
  public ActivityRecord {
    if (id == null || capturedAt == null || durationEstimate < 0 || !Double.isFinite(confidence)
        || confidence < 0 || confidence > 1) throw new IllegalArgumentException("Invalid activity record");
    observations = List.copyOf(observations); screenshotReferences = List.copyOf(screenshotReferences);
  }
  public record Bounds(int x, int y, int width, int height) {}
  public record Observation(String monitor, Bounds bounds, Instant capturedAt) {}
  public record Inference(String summary, List<Activity> activities) {
    public Inference { activities = List.copyOf(activities); }
  }
  /** All fields here, including visible application/content, are model candidates, not OS facts. */
  public record Activity(String monitor, String type, String application, String service,
      String contentTitle, String projectCandidate) {}
}
