package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.*;

public final class ActivityEvidenceAggregator {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ActivityEvidenceAggregator.class);
  private final List<ActivityEvidenceSource> sources;
  private final CapturePolicy policy;
  public ActivityEvidenceAggregator(ActivityProperties properties,List<ActivityEvidenceSource> sources) {
    this.sources=List.copyOf(sources);policy=new CapturePolicy(properties);
  }
  public ActivityEvidence collect(Instant at,DesktopActivityObserver.Metadata metadata,ActivityRecord prior) {
    String project="",projectId="";var events=new ArrayList<ActivityEvidence.RecentEvent>();
    for(var source:sources) try {
      var c=source.collect(at);if(c==null)continue;
      if(c.projectName()!=null && !c.projectName().isBlank()) {project=c.projectName();projectId=c.projectId();}
      events.addAll(c.events());
    } catch(Exception e) {log.warn("Activity evidence source unavailable ({})",e.getClass().getSimpleName());}
    var visible=metadata.visibleWindows()==null?List.<ActivityEvidence.VisibleWindow>of():metadata.visibleWindows().stream()
        .filter(w->w!=null && w.visible() && !w.minimized() && !w.offScreen() && !policy.excluded(w.window()))
        .filter(w->!w.window().windowId().equals(metadata.foreground().windowId())).limit(32).toList();
    var history=prior==null?null:new ActivityEvidence.History(prior.capturedAt(),prior.foreground(),prior.inference(),prior.confidence());
    return new ActivityEvidence(at,metadata.foreground(),visible,project,projectId,events.stream().limit(16).toList(),history);
  }
}
