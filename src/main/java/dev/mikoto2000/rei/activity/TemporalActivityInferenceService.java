package dev.mikoto2000.rei.activity;

import java.time.*;
import java.util.*;
import java.util.function.*;

/** Runs on its own single worker. A supplement failure cannot stop observation or Vision. */
public final class TemporalActivityInferenceService {
  public record Outcome(String activity,double confidence,long totalTokens) {}
  @FunctionalInterface public interface Model {Outcome infer(String input,int maxOutputTokens,int timeoutSeconds,long maxTotalTokens)throws Exception;}
  public static final class BudgetFailure extends RuntimeException {
    final long tokens;public BudgetFailure(){this(0);}public BudgetFailure(long tokens){super("Temporal token accounting unavailable or exhausted");this.tokens=tokens;}
  }
  public static final class ModelFailure extends IllegalArgumentException {
    final long tokens;final boolean retryable;
    public ModelFailure(long tokens,boolean retryable,Throwable cause){super("Temporal completion rejected",cause);this.tokens=tokens;this.retryable=retryable;}
  }
  public record Metrics(long completed,long failed,long skipped,long llmCalls,long totalTokens,long inputChars) {}
  private final ActivityProperties p;private final ActivityStore records;private final WorkActivityInferenceStore store;private final Model model;
  private final Clock clock;private final Supplier<List<WorkActivityInference.Execution>> executions;private final BooleanSupplier paused;
  private Instant lastAttempt;private String fingerprint;private WorkActivityInference candidate;private int attempts;private long tokens;
  private TemporalActivityEvidence candidateEvidence;
  private int candidateCalls;
  private boolean savePending;
  private boolean initialized;private long completed,failed,skipped,llmCalls,totalTokens,inputChars;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(TemporalActivityInferenceService.class);
  public TemporalActivityInferenceService(ActivityProperties p,ActivityStore records,WorkActivityInferenceStore store,Model model,Clock clock,
      Supplier<List<WorkActivityInference.Execution>> executions,BooleanSupplier paused) {
    this.p=p;this.records=records;this.store=store;this.model=model;this.clock=clock;this.executions=executions;this.paused=paused;
  }
  public synchronized Metrics metrics(){return new Metrics(completed,failed,skipped,llmCalls,totalTokens,inputChars);}
  public synchronized void tick() {
    if(!p.isEnabled() || !p.getTemporal().isEnabled() || paused.getAsBoolean())return;
    var now=clock.instant();var settings=p.getTemporal();
    if(lastAttempt!=null && !now.isBefore(lastAttempt) && now.isBefore(lastAttempt.plusSeconds(settings.getIntervalSeconds())))return;
    lastAttempt=now;
    try {
      var input=TemporalActivityEvidence.build(records.findObservationsBetweenBounded(now.minusSeconds(settings.getWindowSeconds()),now.plusMillis(1),settings.getMaxRecords()),
          executions.get(),now.minusSeconds(settings.getWindowSeconds()),now);
      if(input.observationIds().isEmpty()){fingerprint=null;candidate=null;candidateEvidence=null;savePending=false;skipped++;return;}
      if(candidateEvidence!=null && !input.fingerprint().equals(candidateEvidence.fingerprint())){candidate=null;candidateEvidence=null;savePending=false;}
      if(!initialized) {
        initialized=true;var latest=store.latest();
        if(latest.isPresent() && !latest.get().windowEnd().isAfter(now) && !latest.get().windowEnd().isBefore(now.minusSeconds(settings.getWindowSeconds())))fingerprint=latest.get().inputHash();
      }
      if(!input.fingerprint().equals(fingerprint)) {
        if(candidateEvidence==null || !input.fingerprint().equals(candidateEvidence.fingerprint())) {
          candidate=input.ruleInference();candidateEvidence=input;attempts=0;candidateCalls=0;tokens=0;savePending=false;
        }
      }else if(!savePending && (candidate==null || attempts>settings.getMaxRetries() || !candidate.method().equals("LLM_FAILED_RULES"))){skipped++;return;}
      var result=candidate;boolean retry=false;
      if(!savePending && settings.isLlmEnabled() && model!=null && candidate.confidence()>0 && input.groups().stream().anyMatch(g->!g.facts().content().isBlank())) {
        try {
          String structured=candidateEvidence.structuredInput(settings.getMaxInputChars());inputChars+=structured.length();
          if(tokens>=settings.getMaxTotalTokens())throw new BudgetFailure();
          lastAttempt=clock.instant();attempts++;candidateCalls++;llmCalls++;
          var output=model.infer(structured,settings.getMaxOutputTokens(),settings.getTimeoutSeconds(),settings.getMaxTotalTokens()-tokens);
          if(output==null || output.totalTokens()<=0)throw new BudgetFailure();
          if(output.totalTokens()>settings.getMaxTotalTokens()-tokens)throw new BudgetFailure(output.totalTokens());
          tokens+=output.totalTokens();totalTokens+=output.totalTokens();
          if(output.activity()==null || output.activity().isBlank() || output.activity().length()>240 || !Double.isFinite(output.confidence()) || output.confidence()<0 || output.confidence()>.8
              || output.activity().matches("(?s).*(ユーザー[がは]|成功した|完了した|集中して).*"))throw new IllegalArgumentException("Unsupported temporal inference");
          result=copy(candidate,"画面・れいの記録からの推定: "+TemporalActivityEvidence.clean(output.activity()),Math.min(.7,output.confidence()),"LLM",candidateCalls,tokens);
        }catch(Exception e) {
          long consumed=e instanceof BudgetFailure b?b.tokens:e instanceof ModelFailure m?m.tokens:0;
          tokens+=consumed;totalTokens+=consumed;
          if(e instanceof BudgetFailure || (e instanceof ModelFailure m?!m.retryable:e instanceof IllegalArgumentException))attempts=settings.getMaxRetries()+1;
          retry=true;failed++;result=copy(candidate,candidate.inferredActivity(),candidate.confidence(),"LLM_FAILED_RULES",candidateCalls,tokens);
          log.warn("Activity temporal model failed: reason={} base_records_retained=true",e.getClass().getSimpleName());
        }
      }
      candidate=result;if(!retry && !savePending)attempts=settings.getMaxRetries()+1;
      savePending=true;store.save(result);savePending=false;fingerprint=input.fingerprint();completed++;
    }catch(Exception e){failed++;log.warn("Activity temporal inference failed: reason={} base_records_retained=true",e.getClass().getSimpleName());}
    finally {var m=metrics();log.info("Activity temporal metrics: completed={} failed={} skipped={} llm_calls={} total_tokens={} input_chars={}",m.completed(),m.failed(),m.skipped(),m.llmCalls(),m.totalTokens(),m.inputChars());}
  }
  private WorkActivityInference copy(WorkActivityInference r,String activity,double confidence,String method,int calls,long tokens) {
    return new WorkActivityInference(r.id(),r.inputHash(),clock.instant(),r.windowStart(),r.windowEnd(),r.projectId(),r.project(),activity,r.observationIds(),r.executions(),confidence,confidence==0?"UNKNOWN":r.status(),method,calls,tokens);
  }
}
