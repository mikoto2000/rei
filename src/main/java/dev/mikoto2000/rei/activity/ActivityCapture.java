package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded scheduled worker. Pause never waits for network inference; generations invalidate in-flight work. */
public final class ActivityCapture implements AutoCloseable {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ActivityCapture.class);
  private final ActivityProperties properties;
  private final DesktopActivityObserver observer;
  private final ActivityExtractor extractor;
  private final ActivityStore store;
  private final ScreenshotStore screenshots;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();
  private boolean paused, closed;
  private long generation;
  private String continuityId=UUID.randomUUID().toString();
  private ActivityRecord previous;
  private Map<String,double[]> fingerprints = Map.of();
  public ActivityCapture(ActivityProperties properties, DesktopActivityObserver observer, ActivityExtractor extractor,
      ActivityStore store, ScreenshotStore screenshots, Clock clock) {
    properties.validate();
    this.properties=properties; this.observer=observer; this.extractor=extractor; this.store=store; this.screenshots=screenshots; this.clock=clock;
  }
  public synchronized void pause() { paused=true; generation++; resetEvidence(); }
  public synchronized void resume() { paused=false; generation++; resetEvidence(); }
  public synchronized boolean isPaused() { return paused; }
  @Override public synchronized void close() { closed=true; pause(); }
  private synchronized boolean allowed(long token) { return properties.isEnabled() && !paused && !closed && token==generation; }

  public void tick() {
    if (!running.compareAndSet(false,true)) return;
    try {
      try { screenshots.cleanup(clock.instant().minus(Duration.ofDays(properties.getScreenshotRetentionDays()))); }
      catch (Exception e) { failure("retention",e); }
      long token; synchronized(this) { token=generation; }
      if (!allowed(token)) return;
      var foreground=observer.foreground();
      if (new CapturePolicy(properties).excluded(foreground)) { resetEvidence(); return; }
      dev.mikoto2000.rei.computeruse.CapturedScreen screen;
      Instant at;
      synchronized(this) {
        if (!allowed(token)) return;
        at=clock.instant(); screen=observer.capture();
      }
      if (!allowed(token)) return;
      if (!Objects.equals(foreground,observer.foreground())) { resetEvidence(); return; }
      var observations=screen.displays().stream().map(d -> {
        var b=d.geometry().bounds();
        return new ActivityRecord.Observation(d.geometry().id(),new ActivityRecord.Bounds(b.x,b.y,b.width,b.height),at);
      }).toList();
      var current=new HashMap<String,double[]>();
      screen.displays().forEach(d -> current.put(d.geometry().id(),ImageChange.fingerprint(d.image())));
      ActivityRecord prior; Map<String,double[]> baseline;
      synchronized(this) { prior=previous; baseline=fingerprints; }
      double change=1;
      if (prior!=null && current.keySet().equals(baseline.keySet())) {
        change=current.entrySet().stream().mapToDouble(e -> ImageChange.distance(e.getValue(),baseline.get(e.getKey()))).max().orElse(1);
      }
      boolean duplicate=prior!=null && Objects.equals(prior.foreground(),foreground)
          && sameGeometry(prior.observations(),observations) && change<=properties.getChangeThreshold()
          && Duration.between(prior.capturedAt(),at).getSeconds()<=Math.max(properties.getSessionGapSeconds(),properties.getCaptureIntervalSeconds()*2L);
      if (!allowed(token)) return;
      var result=duplicate ? new ActivityExtractor.Result(prior.inference(),prior.confidence())
          : properties.isExtractionEnabled() ? extractor.extract(screen,foreground)
          : new ActivityExtractor.Result(new ActivityRecord.Inference("OS observation only; activity unknown",List.of()),0);
      synchronized(this) {
        if (!allowed(token)) return;
        var id=UUID.randomUUID().toString();
        var refs=duplicate ? prior.screenshotReferences() : properties.getScreenshotRetentionDays()==0 ? List.<String>of() : screenshots.save(id,at,screen);
        var record=new ActivityRecord(id,at,properties.getCaptureIntervalSeconds(),observations,foreground,result.inference(),result.confidence(),refs,change,duplicate,continuityId);
        store.append(record);
        previous=record;
        // Compare to the last analyzed image, not the last sample: slow cumulative changes still trigger extraction.
        if (!duplicate) fingerprints=Map.copyOf(current);
      }
    } catch (Exception e) { resetEvidence(); failure("capture/extraction/storage",e); }
    finally { running.set(false); }
  }
  private synchronized void resetEvidence() { previous=null; fingerprints=Map.of(); continuityId=UUID.randomUUID().toString(); }
  private static boolean sameGeometry(List<ActivityRecord.Observation> a,List<ActivityRecord.Observation> b) {
    return a.stream().map(o -> Map.entry(o.monitor(),o.bounds())).toList().equals(b.stream().map(o -> Map.entry(o.monitor(),o.bounds())).toList());
  }
  private static void failure(String stage, Exception error) {
    // Never log window titles, screenshot content, provider payloads or parser source text.
    log.warn("Activity {} failed ({})",stage,error.getClass().getSimpleName());
  }
}
