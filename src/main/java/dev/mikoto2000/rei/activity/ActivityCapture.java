package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observation and inference have separate workers. Only the latest pending image is retained in memory. */
public final class ActivityCapture implements AutoCloseable {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ActivityCapture.class);
  private final ActivityProperties properties;
  private final DesktopActivityObserver observer;
  private final ActivityExtractor extractor;
  private final ActivityStore store;
  private final ScreenshotStore screenshots;
  private final Clock clock;
  private final ActivityEvidencePipeline evidencePipeline;
  private final java.util.concurrent.Executor analysisExecutor;
  private final java.util.concurrent.Executor backgroundExecutor;
  private final AtomicBoolean running = new AtomicBoolean();
  private boolean analyzing;
  private boolean analyzingBackground;
  private Frame pending;
  private static final class Frame {
    final long token,queuedAt;
    final Instant at;
    final ForegroundWindow foreground;
    final dev.mikoto2000.rei.computeruse.CapturedScreen screen;
    // Accessed only under the ActivityCapture monitor. Never retained beyond this frame's workers.
    ActivityRecord saved;
    ActivityExtractor.Result backgroundResult;
    Frame(long token,Instant at,ForegroundWindow foreground,dev.mikoto2000.rei.computeruse.CapturedScreen screen,long queuedAt) {
      this.token=token;this.at=at;this.foreground=foreground;this.screen=screen;this.queuedAt=queuedAt;
    }
    long token(){return token;} long queuedAt(){return queuedAt;}
    Instant at(){return at;} ForegroundWindow foreground(){return foreground;}
    dev.mikoto2000.rei.computeruse.CapturedScreen screen(){return screen;}
  }
  private boolean paused, closed;
  private long generation;
  private String continuityId=UUID.randomUUID().toString();
  private ActivityRecord previous;
  private Map<String,double[]> fingerprints = Map.of();
  private Map<String,double[]> backgroundFingerprints = Map.of();
  private Instant backgroundCheckedAt;
  public ActivityCapture(ActivityProperties properties, DesktopActivityObserver observer, ActivityExtractor extractor,
      ActivityStore store, ScreenshotStore screenshots, Clock clock) {
    this(properties,observer,extractor,store,screenshots,clock,Runnable::run);
  }
  public ActivityCapture(ActivityProperties properties, DesktopActivityObserver observer, ActivityExtractor extractor,
      ActivityStore store, ScreenshotStore screenshots, Clock clock,java.util.concurrent.Executor analysisExecutor) {
    this(properties,observer,extractor,store,screenshots,clock,analysisExecutor,Runnable::run);
  }
  public ActivityCapture(ActivityProperties properties, DesktopActivityObserver observer, ActivityExtractor extractor,
      ActivityStore store, ScreenshotStore screenshots, Clock clock,java.util.concurrent.Executor analysisExecutor,
      java.util.concurrent.Executor backgroundExecutor) {
    this(properties,observer,extractor,store,screenshots,clock,analysisExecutor,backgroundExecutor,List.of());
  }
  public ActivityCapture(ActivityProperties properties, DesktopActivityObserver observer, ActivityExtractor extractor,
      ActivityStore store, ScreenshotStore screenshots, Clock clock,java.util.concurrent.Executor analysisExecutor,
      java.util.concurrent.Executor backgroundExecutor,List<ActivityEvidenceSource> sources) {
    properties.validate();
    this.properties=properties; this.observer=observer; this.extractor=extractor; this.store=store; this.screenshots=screenshots; this.clock=clock;
    this.analysisExecutor=analysisExecutor;
    this.backgroundExecutor=backgroundExecutor;
    this.evidencePipeline=new ActivityEvidencePipeline(properties,observer,extractor,store,screenshots,clock,analysisExecutor,backgroundExecutor,sources);
  }
  public synchronized void pause() { paused=true; invalidate();evidencePipeline.pause(); }
  public synchronized void resume() { paused=false; invalidate();evidencePipeline.resume(); }
  public synchronized boolean isPaused() { return paused; }
  @Override public synchronized void close() { closed=true; pause();evidencePipeline.close(); }
  private synchronized boolean allowed(long token) { return properties.isEnabled() && !paused && !closed && token==generation; }

  public void tick() {
    if(properties.getDetection().getMode()==ActivityProperties.DetectionMode.EVIDENCE_FIRST){evidencePipeline.tick();return;}
    if (!running.compareAndSet(false,true)) return;
    long started=System.nanoTime();String status="skipped";
    try {
      try { screenshots.cleanup(clock.instant().minus(Duration.ofDays(properties.getScreenshotRetentionDays()))); }
      catch (Exception e) { failure("retention",e); }
      long token; synchronized(this) { token=generation; }
      if (!allowed(token)) return;
      var foreground=observer.foreground();
      if (new CapturePolicy(properties).excluded(foreground)) { invalidate(); return; }
      dev.mikoto2000.rei.computeruse.CapturedScreen screen;
      Instant at;
      synchronized(this) {
        if (!allowed(token)) return;
        at=clock.instant(); screen=observer.capture();
      }
      if (!allowed(token)) return;
      if (!Objects.equals(foreground,observer.foreground())) { invalidate(); return; }
      long observed=System.nanoTime();
      log.info("Activity observation timing: observe_ms={} displays={}",ms(observed-started),screen.displays().size());
      var frame=new Frame(token,at,foreground,screen,observed);
      boolean background=reserveBackground(frame);
      submit(frame);
      if(background) submitBackground(frame);
      status="submitted";
    } catch (Exception e) { status="failed";invalidate(); failure("observation",e); }
    finally { if(!status.equals("submitted")) log.debug("Activity observation: status={} elapsed_ms={}",status,ms(System.nanoTime()-started));running.set(false); }
  }
  private void submit(Frame frame) {
    synchronized(this) {
      if(!allowed(frame.token())) return;
      if(pending!=null) log.info("Activity analysis backlog: replaced_pending=1 age_ms={}",ms(System.nanoTime()-pending.queuedAt()));
      pending=frame;
      if(analyzing) return;
      analyzing=true;
    }
    try { analysisExecutor.execute(this::drain); }
    catch(RuntimeException e) { synchronized(this) {analyzing=false;pending=null;} failure("analysis scheduling",e); }
  }
  private void drain() {
    while(true) {
      Frame frame;
      synchronized(this) {
        frame=pending;pending=null;
        if(frame==null) {analyzing=false;return;}
      }
      analyze(frame);
    }
  }
  private synchronized boolean reserveBackground(Frame frame) {
    if(!properties.getDetection().isBackgroundFullScreenEnabled() || !properties.getDetection().isVisionEnabled())return false;
    if(!allowed(frame.token()) || !properties.isExtractionEnabled() || ActivityImages.foreground(frame.screen(),frame.foreground())==frame.screen()) return false;
    if(analyzingBackground) {log.info("Activity background skipped: reason=busy");return false;}
    if(backgroundCheckedAt!=null && Duration.between(backgroundCheckedAt,frame.at()).getSeconds()<properties.getBackgroundAnalysisIntervalSeconds()) return false;
    var current=ActivityImages.backgroundFingerprints(frame.screen(),frame.foreground());
    boolean changed=backgroundCheckedAt!=null && distance(current,backgroundFingerprints)>properties.getChangeThreshold();
    backgroundCheckedAt=frame.at();backgroundFingerprints=current;
    if(changed) analyzingBackground=true;
    return changed;
  }
  private void submitBackground(Frame frame) {
    try {backgroundExecutor.execute(()->analyzeBackground(frame));}
    catch(RuntimeException e) {synchronized(this) {analyzingBackground=false;backgroundCheckedAt=null;}failure("background scheduling",e);}
  }
  private void analyzeBackground(Frame frame) {
    try {
      if(!allowed(frame.token())) return;
      log.info("Activity analysis timing: queue_wait_ms={} scope=background duplicate=false",ms(System.nanoTime()-frame.queuedAt()));
      var result=extract(frame.screen(),frame.foreground(),"background");
      synchronized(this) {
        if(!allowed(frame.token())) return;
        frame.backgroundResult=result;
        if(frame.saved!=null) {
          var enriched=ActivityBackgroundMerge.merge(frame.saved,result,properties.getPrimaryConfidenceThreshold());
          store.replace(enriched);
          log.info("Activity background supplemented: observation_age_ms={}",Duration.between(frame.at(),clock.instant()).toMillis());
        }
      }
    } catch(Exception e) {
      saveEvidence(frame.token(),UUID.randomUUID().toString(),frame.at(),frame.screen(),false,ScreenshotPersistencePolicy.Outcome.EXTRACTION_FAILURE);
      failure("background extraction/storage",e);
    } finally {synchronized(this) {analyzingBackground=false;}}
  }
  private void analyze(Frame frame) {
    long token=frame.token();var at=frame.at();var foreground=frame.foreground();var screen=frame.screen();
    if(!allowed(token)) return;
    long started=System.nanoTime();
    try {
      var observations=screen.displays().stream().map(d -> {
        var b=d.geometry().bounds();
        return new ActivityRecord.Observation(d.geometry().id(),new ActivityRecord.Bounds(b.x,b.y,b.width,b.height),at);
      }).toList();
      var front=properties.getDetection().isForegroundCrop()?ActivityImages.foreground(screen,foreground):screen;
      var current=fingerprints(front);
      ActivityRecord prior; Map<String,double[]> baseline;
      synchronized(this) {
        prior=previous; baseline=fingerprints;
      }
      double change=prior==null?1:distance(current,baseline);
      boolean duplicate=prior!=null && Objects.equals(prior.foreground(),foreground)
          && sameGeometry(prior.observations(),observations) && change<=properties.getChangeThreshold()
          && Duration.between(prior.capturedAt(),at).getSeconds()<=Math.max(properties.getSessionGapSeconds(),properties.getCaptureIntervalSeconds()*2L);
      if (!allowed(token)) return;
      log.info("Activity analysis timing: queue_wait_ms={} change_check_ms={} scope={} duplicate={}",
          ms(started-frame.queuedAt()),ms(System.nanoTime()-started),front==screen?"desktop_fallback":"foreground",duplicate);
      ActivityExtractor.Result result;
      try {
        result=duplicate ? new ActivityExtractor.Result(prior.inference(),prior.confidence())
            : properties.isExtractionEnabled() && properties.getDetection().isVisionEnabled() ? extract(front,foreground,"foreground")
            : new ActivityExtractor.Result(new ActivityRecord.Inference("OS observation only; activity unknown",List.of()),0);
      } catch (Exception error) {
        saveEvidence(token, UUID.randomUUID().toString(), at, screen, duplicate,
            ScreenshotPersistencePolicy.Outcome.EXTRACTION_FAILURE);
        synchronized(this) {if(allowed(token)) resetEvidence();}
        failure("extraction", error);
        return;
      }
      synchronized(this) {
        if (!allowed(token)) return;
        long saveStarted=System.nanoTime();
        var id=UUID.randomUUID().toString();
        var refs=duplicate ? prior.screenshotReferences() : saveEvidence(token, id, at, screen, false,
            ScreenshotPersistencePolicy.Outcome.SUCCESS);
        var record=new ActivityRecord(id,at,properties.getCaptureIntervalSeconds(),observations,foreground,result.inference(),result.confidence(),refs,change,duplicate,continuityId);
        store.append(frame.backgroundResult==null?record:ActivityBackgroundMerge.merge(record,frame.backgroundResult,properties.getPrimaryConfidenceThreshold()));
        frame.saved=record;
        log.info("Activity record saved: storage_ms={} observation_age_ms={} duplicate={}",ms(System.nanoTime()-saveStarted),Duration.between(at,clock.instant()).toMillis(),duplicate);
        previous=record;
        // Compare to the last analyzed image, not the last sample: slow cumulative changes still trigger extraction.
        // Keep foreground-only evidence in the reuse cache, even after background supplementation.
        if (!duplicate) fingerprints=Map.copyOf(current);
      }
    } catch (Exception e) { synchronized(this) {if(allowed(token)) resetEvidence();} failure("extraction/storage",e); }
  }
  private synchronized List<String> saveEvidence(long token, String id, Instant at,
      dev.mikoto2000.rei.computeruse.CapturedScreen screen, boolean duplicate,
      ScreenshotPersistencePolicy.Outcome outcome) {
    // Called only after the foreground privacy checks. A pause/close invalidates in-flight failures too.
    if (!allowed(token) || !new ScreenshotPersistencePolicy(properties).shouldSave(duplicate, outcome)) return List.of();
    try { return screenshots.save(id, at, screen); }
    catch (Exception error) {
      failure("evidence persistence", error);
      return List.of(); // A failed optional evidence write must not discard a valid ActivityRecord.
    }
  }
  private ActivityExtractor.Result extract(dev.mikoto2000.rei.computeruse.CapturedScreen screen,ForegroundWindow foreground,String scope) throws Exception {
    try(var ignored=org.slf4j.MDC.putCloseable("activityScope",scope)) {return extractor.extract(screen,foreground);}
  }
  private static Map<String,double[]> fingerprints(dev.mikoto2000.rei.computeruse.CapturedScreen screen) {
    var values=new HashMap<String,double[]>();screen.displays().forEach(d -> values.put(d.geometry().id(),ImageChange.fingerprint(d.image())));return Map.copyOf(values);
  }
  private static double distance(Map<String,double[]> current,Map<String,double[]> baseline) {
    return current.keySet().equals(baseline.keySet())?current.entrySet().stream().mapToDouble(e -> ImageChange.distance(e.getValue(),baseline.get(e.getKey()))).max().orElse(1):1;
  }
  private synchronized void resetEvidence() { previous=null; fingerprints=Map.of(); backgroundFingerprints=Map.of();backgroundCheckedAt=null;continuityId=UUID.randomUUID().toString(); }
  private synchronized void invalidate() {generation++;pending=null;resetEvidence();}
  private static long ms(long nanos) {return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos);}
  private static boolean sameGeometry(List<ActivityRecord.Observation> a,List<ActivityRecord.Observation> b) {
    return a.stream().map(o -> Map.entry(o.monitor(),o.bounds())).toList().equals(b.stream().map(o -> Map.entry(o.monitor(),o.bounds())).toList());
  }
  private static void failure(String stage, Exception error) {
    // Never log window titles, screenshot content, provider payloads or parser source text.
    if (error instanceof ActivityOutputParser.InvalidOutput invalid)
      log.warn("Activity {} failed (InvalidOutput): {}", stage, invalid.diagnostic());
    else log.warn("Activity {} failed ({})",stage,error.getClass().getSimpleName());
  }
}
