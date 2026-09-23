package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.computeruse.CapturedScreen;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.*;

/** Persist metadata first. Bounded image workers only enrich already-persisted observations. */
final class ActivityEvidencePipeline {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ActivityEvidencePipeline.class);
  private final ActivityProperties p;
  private final DesktopActivityObserver observer;
  private final ActivityExtractor extractor;
  private final ActivityStore store;
  private final ScreenshotStore screenshots;
  private final Clock clock;
  private final Executor foregroundExecutor,backgroundExecutor;
  private final ActivityEvidenceAggregator aggregator;
  private final ActivityClassifier classifier=new ActivityClassifier();
  private final AtomicBoolean observing=new AtomicBoolean();
  private final LongAdder observations=new LongAdder(),evidenceOnly=new LongAdder(),fallbacks=new LongAdder(),skipped=new LongAdder(),foregroundCalls=new LongAdder(),backgroundCalls=new LongAdder(),success=new LongAdder(),failure=new LongAdder(),timeout=new LongAdder();
  private boolean paused,closed,foregroundBusy,backgroundBusy;
  private long generation;
  private String continuity=UUID.randomUUID().toString();
  private ActivityRecord previous;
  private Work pending;
  private Instant backgroundAt;
  private static final class Work {
    final long generation;final CapturedScreen screen;
    ActivityRecord record;
    Work(long generation,CapturedScreen screen,ActivityRecord record) {this.generation=generation;this.screen=screen;this.record=record;}
  }
  ActivityEvidencePipeline(ActivityProperties p,DesktopActivityObserver observer,ActivityExtractor extractor,ActivityStore store,
      ScreenshotStore screenshots,Clock clock,Executor foregroundExecutor,Executor backgroundExecutor,List<ActivityEvidenceSource> sources) {
    this.p=p;this.observer=observer;this.extractor=extractor;this.store=store;this.screenshots=screenshots;this.clock=clock;
    this.foregroundExecutor=foregroundExecutor;this.backgroundExecutor=backgroundExecutor;aggregator=new ActivityEvidenceAggregator(p,sources);
  }
  synchronized void pause() {paused=true;invalidate();}
  synchronized void resume() {paused=false;invalidate();}
  synchronized void close() {closed=true;pause();}
  private synchronized void invalidate() {generation++;pending=null;previous=null;backgroundAt=null;continuity=UUID.randomUUID().toString();}
  private synchronized boolean allowed(long token) {return p.isEnabled() && !paused && !closed && generation==token;}
  void tick() {
    if(!observing.compareAndSet(false,true))return;
    long started=System.nanoTime();
    try {
      long token;ActivityRecord prior;synchronized(this){token=generation;prior=previous;}
      try {screenshots.cleanup(clock.instant().minus(Duration.ofDays(p.getScreenshotRetentionDays())));}catch(Exception e){warn("retention",e);}
      if(!allowed(token))return;
      DesktopActivityObserver.Metadata metadata;
      try {metadata=observer.metadata();}catch(Exception e){warn("metadata",e);metadata=null;}
      if(metadata==null)metadata=new DesktopActivityObserver.Metadata(observer.foreground(),List.of());
      var fg=metadata.foreground();
      if(new CapturePolicy(p).excluded(fg)){invalidate();return;}
      var at=clock.instant();
      var evidence=aggregator.collect(at,metadata,prior);
      ActivityClassification classification;
      try {classification=p.getDetection().isEvidenceEnabled()?classifier.classify(evidence):unknown(fg);}
      catch(Exception e){warn("classification",e);classification=unknown(fg);}
      boolean fallback=p.isExtractionEnabled() && p.getDetection().isVisionEnabled() && p.getDetection().isFallbackEnabled()
          && classification.confidence()<p.getDetection().getSkipVisionConfidence();
      ActivityRecord record;
      synchronized(this) {
        if(!allowed(token))return;
        var detection=new ActivityRecord.Detection(evidence,classification.sources(),false,"EVIDENCE_ONLY",fallback?"PROVISIONAL":"FINAL",classification.sourceConfidence(),classification.reason());
        record=new ActivityRecord(UUID.randomUUID().toString(),at,p.getCaptureIntervalSeconds(),List.of(),fg,classification.inference(),classification.confidence(),List.of(),0,false,continuity,detection);
        store.append(record);previous=record;observations.increment();
        if(!fallback){evidenceOnly.increment();skipped.increment();}
        else fallbacks.increment();
      }
      log.info("Activity evidence saved: classification_ms={} confidence={} fallback={}",millis(started),record.confidence(),fallback);
      boolean background;
      synchronized(this) {
        background=p.isExtractionEnabled() && p.getDetection().isVisionEnabled() && p.getDetection().isBackgroundFullScreenEnabled()
            && !backgroundBusy && (backgroundAt==null || Duration.between(backgroundAt,at).toSeconds()>=p.getBackgroundAnalysisIntervalSeconds());
      }
      boolean keep=new ScreenshotPersistencePolicy(p).shouldSave(false,ScreenshotPersistencePolicy.Outcome.SUCCESS);
      if(!fallback && !background && !keep){metrics();return;}
      if(!allowed(token) || !Objects.equals(fg,observer.foreground())) {metrics();return;}
      CapturedScreen screen;
      try {screen=observer.capture();}catch(Exception e){warn("screenshot",e);metrics();return;}
      if(!allowed(token) || !Objects.equals(fg,observer.foreground())) {metrics();return;}
      var work=new Work(token,screen,record);
      if(keep) synchronized(this) {
        if(allowed(token)) {
          try {var refs=screenshots.save(record.id(),at,screen);work.record=copy(work.record,work.record.inference(),work.record.confidence(),work.record.detection(),refs);store.replace(work.record);}
          catch(Exception e){warn("evidence persistence",e);}
        }
      }
      if(fallback)submitForeground(work);
      if(background)submitBackground(work);
      metrics();
    } catch(Exception e){warn("observation",e);} finally {observing.set(false);}
  }
  private void submitForeground(Work work) {
    synchronized(this) {
      if(!allowed(work.generation))return;
      if(pending!=null){log.info("Activity fallback replaced: evidence_retained=true");skipped.increment();}
      pending=work;if(foregroundBusy)return;foregroundBusy=true;
    }
    try {foregroundExecutor.execute(this::drain);}
    catch(RuntimeException e){synchronized(this){foregroundBusy=false;pending=null;}warn("foreground scheduling",e);}
  }
  private void drain() {
    while(true) {
      Work work;synchronized(this){work=pending;pending=null;if(work==null){foregroundBusy=false;return;}}
      enrich(work,false);
    }
  }
  private void submitBackground(Work work) {
    synchronized(this) {
      if(!allowed(work.generation) || backgroundBusy)return;
      backgroundBusy=true;backgroundAt=work.record.capturedAt();
    }
    try {backgroundExecutor.execute(()->{try{enrich(work,true);}finally{synchronized(this){backgroundBusy=false;}}});}
    catch(RuntimeException e){synchronized(this){backgroundBusy=false;}warn("background scheduling",e);}
  }
  private void enrich(Work work,boolean background) {
    long started=System.nanoTime();
    ActivityRecord original;synchronized(this){if(!allowed(work.generation))return;original=work.record;}
    String source=background?"VISION_BACKGROUND":"VISION_FOREGROUND";
    try(var ignored=org.slf4j.MDC.putCloseable("activityScope",background?"background":"foreground")) {
      var image=background || !p.getDetection().isForegroundCrop()?work.screen:ActivityImages.foreground(work.screen,original.foreground());
      if(!background && p.getDetection().isForegroundCrop() && image==work.screen) {
        skipped.increment();log.info("Activity fallback skipped: reason=foreground_bounds_unavailable evidence_retained=true");return;
      }
      if(background)backgroundCalls.increment();else foregroundCalls.increment();
      var result=extractor.extract(image,original.foreground());
      synchronized(this) {
        if(!allowed(work.generation))return;
        var base=work.record;ActivityRecord merged;
        if(background) merged=ActivityBackgroundMerge.merge(base,result,p.getPrimaryConfidenceThreshold());
        else if(result.confidence()>base.confidence()) {
          merged=copy(base,result.inference(),result.confidence(),base.detection(),base.screenshotReferences());
          merged=ActivityBackgroundMerge.merge(merged,new ActivityExtractor.Result(base.inference(),base.confidence()),0);
        } else merged=base;
        var d=merged.detection();var sources=new LinkedHashSet<>(d.classificationSources());sources.add(source);
        var weights=new LinkedHashMap<>(d.sourceConfidence());weights.put(source,result.confidence());
        var detection=new ActivityRecord.Detection(d.evidence(),List.copyOf(sources),true,"EVIDENCE_PLUS_VISION","FINAL",weights,d.reason());
        work.record=copy(merged,merged.inference(),merged.confidence(),detection,merged.screenshotReferences());store.replace(work.record);
        if(previous!=null && previous.id().equals(work.record.id()))previous=work.record;
        success.increment();
      }
      log.info("Activity evidence enriched: scope={} observation_age_ms={}",background?"background":"foreground",Duration.between(original.capturedAt(),clock.instant()).toMillis());
    } catch(Exception e) {
      failure.increment();if(isTimeout(e))timeout.increment();warn(background?"background Vision":"foreground Vision",e);
      synchronized(this) {
        if(allowed(work.generation))try {
          var r=work.record;var d=r.detection();var sources=new LinkedHashSet<>(d.classificationSources());sources.add(source);
          var status=d.status().equals("FINAL")?"FINAL":"VISION_FAILED";
          var detection=new ActivityRecord.Detection(d.evidence(),List.copyOf(sources),true,d.classificationMode(),status,d.sourceConfidence(),d.reason());
          var refs=r.screenshotReferences();
          if(new ScreenshotPersistencePolicy(p).shouldSave(false,ScreenshotPersistencePolicy.Outcome.EXTRACTION_FAILURE))
            try{refs=screenshots.save(r.id(),r.capturedAt(),work.screen);}catch(Exception imageError){warn("failure evidence",imageError);}
          work.record=copy(r,r.inference(),r.confidence(),detection,refs);store.replace(work.record);
        }catch(Exception storageError){warn("failure status",storageError);}
      }
    } finally {
      log.info("Activity enrichment timing: scope={} vision_ms={}",background?"background":"foreground",millis(started));
      metrics();
    }
  }
  private ActivityClassification unknown(ForegroundWindow fg) {
    return new ActivityClassification(new ActivityRecord.Inference("主活動を判定できないOS観測",List.of(new ActivityRecord.Activity("foreground","unknown",fg.processName(),"","",""))),0,List.of("FOREGROUND_WINDOW"),Map.of(),"insufficient_evidence");
  }
  private static ActivityRecord copy(ActivityRecord r,ActivityRecord.Inference inference,double confidence,ActivityRecord.Detection detection,List<String> refs) {
    return new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),inference,confidence,refs,r.changeAmount(),r.duplicate(),r.continuityId(),detection);
  }
  private void metrics() {
    long count=observations.sum(),calls=foregroundCalls.sum()+backgroundCalls.sum();
    log.info("Activity detection metrics: observations={} evidence_only={} vision_fallback={} vision_skipped={} foreground_vision={} background_vision={} vision_success={} vision_failure={} vision_timeout={} vision_call_rate={}",count,evidenceOnly.sum(),fallbacks.sum(),skipped.sum(),foregroundCalls.sum(),backgroundCalls.sum(),success.sum(),failure.sum(),timeout.sum(),count==0?0:(double)calls/count);
  }
  private static boolean isTimeout(Throwable error) {for(int i=0;error!=null && i<8;i++,error=error.getCause())if(error instanceof java.net.SocketTimeoutException || error instanceof java.util.concurrent.TimeoutException || error instanceof java.net.http.HttpTimeoutException)return true;return false;}
  private static long millis(long start){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
  private static void warn(String stage,Exception e){log.warn("Activity evidence {} failed ({})",stage,e.getClass().getSimpleName());}
}
