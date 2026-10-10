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
  InputAwareObservation observation;
  private final DesktopActivityObserver observer;
  private final ActivityExtractor extractor;
  private final ActivityStore store;
  private final ScreenshotStore screenshots;
  private final Clock clock;
  private final Executor foregroundExecutor,backgroundExecutor;
  private final ActivityEvidenceAggregator aggregator;
  private java.util.function.Function<ActivityEvidence,ActivityClassification> classify=new ActivityClassifier()::classify;
  void useToolkit(ClassificationToolkit toolkit){classify=toolkit::classify;}
  private final AtomicBoolean observing=new AtomicBoolean();
  private final LongAdder observations=new LongAdder(),evidenceOnly=new LongAdder(),fallbacks=new LongAdder(),skipped=new LongAdder(),foregroundCalls=new LongAdder(),backgroundCalls=new LongAdder(),success=new LongAdder(),failure=new LongAdder(),timeout=new LongAdder();
  private final LongAdder outputLimits=new LongAdder(),validationFailures=new LongAdder(),unknownCount=new LongAdder(),partialCount=new LongAdder(),usableCount=new LongAdder();
  private boolean paused,closed,foregroundBusy,backgroundBusy;
  private long generation;
  private String continuity=UUID.randomUUID().toString();
  private ActivityRecord previous;
  private Work pending;
  private Instant backgroundAt;
  private Instant foregroundStarted,lastSucceededAt;
  private CandidateKey lastSucceededKey;
  private ActivityExtractor.Result lastSucceededResult;
  private VisionDiagnostics.Timing lastSucceededTiming;
  private final LongAdder generated=new LongAdder(),completed=new LongAdder(),failed=new LongAdder(),replaced=new LongAdder(),duplicateSkipped=new LongAdder(),deferred=new LongAdder();
  private final LongAdder queueWaitMillis=new LongAdder(),executionMillis=new LongAdder(),endToEndMillis=new LongAdder();
  record QueueMetrics(long generated,long started,long completed,long failed,long replaced,long duplicateSkipped,long deferred,
      long apiCalls,long queueWaitMillis,long executionMillis,long endToEndMillis,int pending,boolean running) {}
  synchronized QueueMetrics queueMetrics(){return new QueueMetrics(generated.sum(),foregroundCalls.sum(),completed.sum(),failed.sum(),replaced.sum(),duplicateSkipped.sum(),deferred.sum(),foregroundCalls.sum(),queueWaitMillis.sum(),executionMillis.sum(),endToEndMillis.sum(),pending==null?0:1,foregroundBusy);}
  private record CandidateKey(ForegroundWindow foreground,String projectId,String projectName,String branch,String commit,long revision) {
    static CandidateKey of(ActivityRecord record) {
      return of(record.detection().evidence());
    }
    static CandidateKey of(ActivityEvidence e) {
      var w=e.workContext();var git=w==null?null:w.git();
      return new CandidateKey(e.foreground(),e.projectId(),e.projectName(),git==null?null:git.branch(),git==null?null:git.commit(),w==null?0:w.revision());
    }
  }
  private static final class Work {
    final long generation;final CapturedScreen screen;
    final Instant imageCapturedAt;
    final CandidateKey key;
    ActivityRecord record;
    Work(long generation,CapturedScreen screen,ActivityRecord record,Instant imageCapturedAt) {this.generation=generation;this.screen=screen;this.record=record;this.imageCapturedAt=imageCapturedAt;this.key=CandidateKey.of(record);}
  }
  ActivityEvidencePipeline(ActivityProperties p,DesktopActivityObserver observer,ActivityExtractor extractor,ActivityStore store,
      ScreenshotStore screenshots,Clock clock,Executor foregroundExecutor,Executor backgroundExecutor,List<ActivityEvidenceSource> sources) {
    this.p=p;this.observer=observer;this.extractor=extractor;this.store=store;this.screenshots=screenshots;this.clock=clock;
    this.foregroundExecutor=foregroundExecutor;this.backgroundExecutor=backgroundExecutor;aggregator=new ActivityEvidenceAggregator(p,sources);
  }
  synchronized void pause() {paused=true;invalidate();}
  synchronized void resume() {paused=false;invalidate();}
  synchronized void close() {closed=true;pause();}
  private synchronized void invalidate() {generation++;pending=null;previous=null;backgroundAt=null;lastSucceededAt=null;lastSucceededKey=null;lastSucceededResult=null;lastSucceededTiming=null;continuity=UUID.randomUUID().toString();}
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
      try {classification=p.getDetection().isEvidenceEnabled() && p.getDetection().getMode()!=ActivityProperties.DetectionMode.VISION_FIRST?classify.apply(evidence):unknown(fg);}
      catch(Exception e){warn("classification",e);classification=unknown(fg);}
      boolean fallback=p.isExtractionEnabled() && p.getDetection().isVisionEnabled()
          && (p.getDetection().getMode()==ActivityProperties.DetectionMode.VISION_FIRST || p.getDetection().isFallbackEnabled())
          && !classification.usable(p.getDetection().getSkipVisionConfidence());
      ActivityRecord record;
      synchronized(this) {
        if(!allowed(token))return;
        boolean duplicateVision=fallback && duplicate(CandidateKey.of(evidence),at);
        boolean candidate=fallback;
        if(duplicateVision)fallback=false;
        var detection=new ActivityRecord.Detection(evidence,classification.sources(),false,"EVIDENCE_ONLY",fallback?"PROVISIONAL":"FINAL",classification.sourceConfidence(),classification.reason()+(duplicateVision?"|VISION_DUPLICATE_SKIPPED":""),classification.fieldConfidence(),classification.secondaryConfidence(),null,VisionDiagnostics.initial());
        record=new ActivityRecord(UUID.randomUUID().toString(),at,p.observationDurationSeconds(p.getObservation().isInputAwareEnabled() && observation!=null && observation.lightweightAttempt()),List.of(),fg,classification.inference(),classification.confidence(),List.of(),0,false,continuity,detection);
        if(duplicateVision)record=historicalInference(record);
        store.append(record);previous=record;observations.increment();
        if(candidate)generated.increment();if(duplicateVision)duplicateSkipped.increment();
        if(observation!=null)observation.saved();
        classificationCounts(record,1);
        if(!fallback){evidenceOnly.increment();skipped.increment();}
        else fallbacks.increment();
      }
      log.info("Activity evidence saved: classification_ms={} confidence={} fallback={}",millis(started),record.confidence(),fallback);
      var axes=classification.fieldConfidence();
      log.debug("Activity classification: category={} categoryConfidence={} applicationConfidence={} serviceConfidence={} projectConfidence={} contentConfidence={} usable={} complete={} visionSkipped={} matchedRule={}",
          record.inference().activities().getFirst().type(),axes.category(),axes.application(),axes.service(),axes.project(),axes.content(),classification.usable(p.getDetection().getSkipVisionConfidence()),classification.complete(),!fallback,classification.reason());
      boolean background;
      synchronized(this) {
        background=p.isExtractionEnabled() && p.getDetection().isVisionEnabled() && p.getDetection().isBackgroundFullScreenEnabled()
            && !backgroundBusy && (backgroundAt==null || Duration.between(backgroundAt,at).toSeconds()>=p.getBackgroundAnalysisIntervalSeconds());
      }
      boolean keep=new ScreenshotPersistencePolicy(p).shouldSave(false,ScreenshotPersistencePolicy.Outcome.SUCCESS);
      if(!fallback && !background && !keep){metrics();return;}
      if(!allowed(token) || !Objects.equals(fg,observer.foreground())) {metrics();return;}
      CapturedScreen screen;
      Instant imageCapturedAt;
      try {screen=observer.capture();imageCapturedAt=clock.instant();}catch(Exception e){warn("screenshot",e);metrics();return;}
      if(!allowed(token) || !Objects.equals(fg,observer.foreground())) {metrics();return;}
      var work=new Work(token,screen,record,imageCapturedAt);
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
      if(pending!=null){log.info("Activity fallback replaced: evidence_retained=true");skipped.increment();replaced.increment();}
      pending=work;
    }
    pollForeground();
  }
  void pollForeground() {
    synchronized(this) {
      if(foregroundBusy || pending==null || !allowed(pending.generation))return;
      if(!startDue()){deferred.increment();return;}
      foregroundBusy=true;
    }
    try {foregroundExecutor.execute(this::drain);}
    catch(RuntimeException e){synchronized(this){foregroundBusy=false;pending=null;}warn("foreground scheduling",e);}
  }
  private void drain() {
    while(true) {
      Work work;synchronized(this){
        work=pending;if(work==null){foregroundBusy=false;return;}
        if(!startDue()){deferred.increment();foregroundBusy=false;return;}
        pending=null;
        if(duplicate(work.key,clock.instant())){duplicateSkipped.increment();if(allowed(work.generation))work.record=finishSkipped(work.record);continue;}
      }
      enrich(work,false);
    }
  }
  private boolean startDue(){return !p.getVisionQueue().isEnabled() || foregroundStarted==null || !clock.instant().isBefore(foregroundStarted.plusSeconds(p.getVisionQueue().getMinimumStartIntervalSeconds()));}
  private boolean duplicate(CandidateKey key,Instant at) {
    return p.getVisionQueue().isEnabled() && Objects.equals(key,lastSucceededKey) && lastSucceededAt!=null
        && !at.isBefore(lastSucceededAt) && Duration.between(lastSucceededAt,at).getSeconds()<p.getVisionQueue().getMaxRefreshIntervalSeconds();
  }
  private ActivityRecord finishSkipped(ActivityRecord record) {
    var d=record.detection();
    var detection=new ActivityRecord.Detection(d.evidence(),d.classificationSources(),d.visionUsed(),d.classificationMode(),"FINAL",d.sourceConfidence(),d.reason()+"|VISION_DUPLICATE_SKIPPED",d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),d.visionDiagnostics());
    var updated=copy(record,record.inference(),record.confidence(),detection,record.screenshotReferences());
    try{store.replace(updated);if(previous!=null && previous.id().equals(updated.id()))previous=updated;return updated;}
    catch(Exception e){warn("duplicate status",e);return record;}
  }
  private ActivityRecord historicalInference(ActivityRecord record) {
    // Results completed after this OS observation are never propagated to it.
    if(lastSucceededResult==null || lastSucceededTiming==null || lastSucceededTiming.completedAt()==null
        || lastSucceededTiming.completedAt().isAfter(record.capturedAt()) || lastSucceededResult.confidence()<p.getPrimaryConfidenceThreshold())return record;
    double confidence=Math.min(.7,lastSucceededResult.confidence());
    var inferred=ActivityEnrichment.merge(record,new ActivityExtractor.Result(lastSucceededResult.inference(),confidence));
    var d=inferred.detection();var sources=new LinkedHashSet<>(d.classificationSources());sources.add("HISTORY");
    var weights=new LinkedHashMap<>(d.sourceConfidence());weights.put("HISTORY",confidence);
    var vision=VisionDiagnostics.of(d).timing(false,lastSucceededTiming);
    var detection=new ActivityRecord.Detection(d.evidence(),List.copyOf(sources),false,"EVIDENCE_PLUS_HISTORY","FINAL",weights,d.reason()+"|HISTORICAL_INFERENCE",d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),vision);
    return copy(inferred,inferred.inference(),inferred.confidence(),detection,inferred.screenshotReferences());
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
    Instant apiStarted=null,apiCompleted=null;boolean supplementSaved=false;
    ActivityRecord original;synchronized(this){if(!allowed(work.generation))return;original=work.record;}
    String source=background?"VISION_BACKGROUND":"VISION_FOREGROUND";
    try(var ignored=org.slf4j.MDC.putCloseable("activityScope",background?"background":"foreground")) {
      var image=background || !p.getDetection().isForegroundCrop()?work.screen:ActivityImages.foreground(work.screen,original.foreground());
      if(!background && p.getDetection().isForegroundCrop() && image==work.screen) {
        skipped.increment();log.info("Activity fallback skipped: reason=foreground_bounds_unavailable evidence_retained=true");return;
      }
      apiStarted=clock.instant();
      synchronized(this){if(!allowed(work.generation))return;if(background)backgroundCalls.increment();else{foregroundCalls.increment();foregroundStarted=apiStarted;}}
      recordAttempt(work,background,apiStarted);
      var result=extractor.extract(image,original.foreground());
      apiCompleted=clock.instant();
      synchronized(this) {
        if(!allowed(work.generation))return;
        var base=work.record;ActivityRecord merged;
        if(background) merged=ActivityBackgroundMerge.merge(base,result,p.getPrimaryConfidenceThreshold());
        else merged=ActivityEnrichment.merge(base,result);
        var d=merged.detection();var sources=new LinkedHashSet<>(d.classificationSources());sources.add(source);
        var weights=new LinkedHashMap<>(d.sourceConfidence());weights.put(source,result.confidence());
        var secondary=new ArrayList<>(d.secondaryConfidence());
        if(background)for(var candidate:merged.inference().activities())
          if(!base.inference().activities().contains(candidate))secondary.add(new ActivityClassification.Secondary(candidate,ActivityFieldConfidence.from(candidate,result.confidence()).secondary()));
        boolean used=!base.inference().equals(merged.inference()) || !ActivityEnrichment.fields(base).equals(ActivityEnrichment.fields(merged));
        var vision=VisionDiagnostics.of(d).with(background,used?VisionDiagnostics.State.USED:VisionDiagnostics.State.ATTEMPTED_SUCCEEDED_NOT_USED,null)
            .timing(background,new VisionDiagnostics.Timing(original.id(),work.imageCapturedAt,apiStarted,apiCompleted));
        var detection=new ActivityRecord.Detection(d.evidence(),List.copyOf(sources),true,"EVIDENCE_PLUS_VISION","FINAL",weights,d.reason(),d.fieldConfidence(),secondary,d.diagnostics(),vision);
        var updated=copy(merged,merged.inference(),merged.confidence(),detection,merged.screenshotReferences());store.replace(updated);work.record=updated;
        supplementSaved=true;
        if(!background){completed.increment();lastSucceededKey=work.key;lastSucceededAt=work.imageCapturedAt;lastSucceededResult=result;lastSucceededTiming=vision.foreground().timing();}
        classificationCounts(base,-1);classificationCounts(work.record,1);
        if(previous!=null && previous.id().equals(work.record.id()))previous=work.record;
        success.increment();
      }
      log.info("Activity evidence enriched: scope={} observation_age_ms={}",background?"background":"foreground",Duration.between(original.capturedAt(),clock.instant()).toMillis());
    } catch(Exception e) {
      if(apiStarted!=null && apiCompleted==null)apiCompleted=clock.instant();
      if(!background)failed.increment();
      failure.increment();var kind=ActivityVisionFailure.classify(e);
      switch(kind){case OUTPUT_LIMIT->outputLimits.increment();case TIMEOUT->timeout.increment();case VALIDATION->validationFailures.increment();default->{}}
      log.warn("Activity Vision failure: scope={} reason={} evidence_retained=true",background?"background":"foreground",kind);
      synchronized(this) {
        if(allowed(work.generation))try {
          var r=work.record;var d=r.detection();var sources=new LinkedHashSet<>(d.classificationSources());sources.add(source);
          var status=d.status().equals("FINAL")?"FINAL":"VISION_FAILED";
          String reason=d.reason().split("\\|",2)[0]+"|"+switch(kind){case OUTPUT_LIMIT->"VISION_OUTPUT_LIMIT";case TIMEOUT->"VISION_TIMEOUT";case VALIDATION->"VISION_VALIDATION_FAILED";default->"VISION_FAILED";};
          var vision=VisionDiagnostics.of(d).with(background,VisionDiagnostics.State.ATTEMPTED_FAILED,kind)
              .timing(background,new VisionDiagnostics.Timing(original.id(),work.imageCapturedAt,apiStarted,apiCompleted));
          var detection=new ActivityRecord.Detection(d.evidence(),List.copyOf(sources),true,d.classificationMode(),status,d.sourceConfidence(),reason,d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),vision);
          var refs=r.screenshotReferences();
          if(new ScreenshotPersistencePolicy(p).shouldSave(false,ScreenshotPersistencePolicy.Outcome.EXTRACTION_FAILURE))
            try{refs=screenshots.save(r.id(),r.capturedAt(),work.screen);}catch(Exception imageError){warn("failure evidence",imageError);}
          work.record=copy(r,r.inference(),r.confidence(),detection,refs);store.replace(work.record);
          supplementSaved=true;
        }catch(Exception storageError){warn("failure status",storageError);}
      }
    } finally {
      if(!background && apiStarted!=null) {
        long wait=elapsed(work.imageCapturedAt,apiStarted),execution=elapsed(apiStarted,apiCompleted==null?clock.instant():apiCompleted);
        queueWaitMillis.add(wait);executionMillis.add(execution);
        long latency=supplementSaved?elapsed(original.capturedAt(),clock.instant()):0;
        if(supplementSaved)endToEndMillis.add(latency);
        log.info("Activity vision observation timing: observationId={} image_at={} started_at={} completed_at={} queue_wait_ms={} execution_ms={} supplement_latency_ms={} saved={}",original.id(),work.imageCapturedAt,apiStarted,apiCompleted,wait,execution,latency,supplementSaved);
      }
      log.info("Activity enrichment timing: scope={} vision_ms={}",background?"background":"foreground",millis(started));
      metrics();
    }
  }
  private ActivityClassification unknown(ForegroundWindow fg) {
    return new ActivityClassification(new ActivityRecord.Inference("主活動を判定できないOS観測",List.of(new ActivityRecord.Activity("foreground","unknown",fg.processName(),"","",""))),0,List.of("FOREGROUND_WINDOW"),Map.of("FOREGROUND_WINDOW",1.0),"insufficient_evidence",new ActivityFieldConfidence(0,1,0,0,0),List.of());
  }
  private synchronized void recordAttempt(Work work,boolean background,Instant startedAt) {
    if(!allowed(work.generation))return;
    var r=work.record;var d=r.detection();
    var vision=VisionDiagnostics.of(d).with(background,VisionDiagnostics.State.ATTEMPTED,null)
        .timing(background,new VisionDiagnostics.Timing(r.id(),work.imageCapturedAt,startedAt,null));
    var detection=new ActivityRecord.Detection(d.evidence(),d.classificationSources(),d.visionUsed(),d.classificationMode(),d.status(),d.sourceConfidence(),d.reason(),d.fieldConfidence(),d.secondaryConfidence(),d.diagnostics(),vision);
    work.record=copy(r,r.inference(),r.confidence(),detection,r.screenshotReferences());
    try {store.replace(work.record);}catch(Exception e){warn("attempt diagnostics",e);}
  }
  private static ActivityRecord copy(ActivityRecord r,ActivityRecord.Inference inference,double confidence,ActivityRecord.Detection detection,List<String> refs) {
    return new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),r.foreground(),inference,confidence,refs,r.changeAmount(),r.duplicate(),r.continuityId(),detection);
  }
  private void metrics() {
    var q=queueMetrics();
    log.info("Activity vision queue metrics: generated={} started={} completed={} failed={} replaced={} duplicate_skipped={} deferred={} api_calls={} queue_wait_total_ms={} execution_total_ms={} supplement_latency_total_ms={} pending={} worker_busy={}",q.generated(),q.started(),q.completed(),q.failed(),q.replaced(),q.duplicateSkipped(),q.deferred(),q.apiCalls(),q.queueWaitMillis(),q.executionMillis(),q.endToEndMillis(),q.pending(),q.running());
    long count=observations.sum(),calls=foregroundCalls.sum()+backgroundCalls.sum();
    log.info("Activity detection metrics: observations={} evidence_only={} vision_fallback={} vision_skipped={} foreground_vision={} background_vision={} vision_success={} vision_failure={} vision_timeout={} vision_call_rate={}",count,evidenceOnly.sum(),fallbacks.sum(),skipped.sum(),foregroundCalls.sum(),backgroundCalls.sum(),success.sum(),failure.sum(),timeout.sum(),count==0?0:(double)calls/count);
    log.info("Activity classification metrics: observations={} unknown={} partial={} vision_output_limit={} vision_validation={} evidence_only_rate={} vision_success_rate={} unknown_rate={} partial_rate={} usable={} usable_rate={}",
        count,unknownCount.sum(),partialCount.sum(),outputLimits.sum(),validationFailures.sum(),rate(evidenceOnly.sum(),count),rate(success.sum(),success.sum()+failure.sum()),rate(unknownCount.sum(),count),rate(partialCount.sum(),count),usableCount.sum(),rate(usableCount.sum(),count));
  }
  private void classificationCounts(ActivityRecord record,int delta) {
    if(record.inference().activities().isEmpty() || ActivityVocabulary.category(record.inference().activities().getFirst().type()).equals("unknown"))unknownCount.add(delta);
    if(ActivityEnrichment.fields(record).partial())partialCount.add(delta);
    if(ActivityEnrichment.fields(record).usable(p.getDetection().getSkipVisionConfidence()))usableCount.add(delta);
  }
  private static double rate(long count,long total){return total==0?0:(double)count/total;}
  private static long elapsed(Instant start,Instant end){return Math.max(0,Duration.between(start,end).toMillis());}
  private static long millis(long start){return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);}
  private static void warn(String stage,Exception e){log.warn("Activity evidence {} failed ({})",stage,e.getClass().getSimpleName());}
}
