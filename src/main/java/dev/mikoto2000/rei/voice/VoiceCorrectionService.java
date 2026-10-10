package dev.mikoto2000.rei.voice;

import java.util.concurrent.*;
import java.util.function.*;
import java.util.*;
import dev.mikoto2000.rei.application.input.ConversationInput;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Bounded admission, enqueue-to-completion deadline, exactly one outcome, and independent late-result invalidation. */
public final class VoiceCorrectionService implements AutoCloseable {
  @FunctionalInterface public interface Llm { String call(String raw,VoiceCorrectionContext.Snapshot context) throws Exception; }
  private final VoiceCorrectionProperties properties;
  private final Function<ConversationInput,VoiceCorrectionContext.Snapshot> contexts;
  private final Llm llm;
  private final VoiceEventPublisher events;
  private final VoiceCorrectionValidator validator=new VoiceCorrectionValidator();
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService deadlines;
  private final LongSupplier ticks;
  private final Map<UUID,Job> jobs=new ConcurrentHashMap<>();
  private volatile boolean closed;
  public record Trace(UUID inputId,String original,String correctionInput,String candidate,String adopted,String submitted,String reason,long atNanos) {}
  private final Map<UUID,Trace> traces=new LinkedHashMap<>();
  private void trace(Job job,VoiceCorrectionValidator.Decision decision) {
    synchronized(traces) {
      traces.values().removeIf(t->ticks.getAsLong()-t.atNanos()>TimeUnit.MINUTES.toNanos(2));
      if(traces.size()>=8)traces.remove(traces.keySet().iterator().next());
      traces.put(job.input.inputId(),new Trace(job.input.inputId(),job.original,job.input.text(),job.candidate,decision.text(),null,decision.reason(),job.queuedAt));
    }
  }
  public List<Trace> traces() { synchronized(traces){traces.values().removeIf(t->ticks.getAsLong()-t.atNanos()>TimeUnit.MINUTES.toNanos(2));return List.copyOf(traces.values());} }
  public void submitted(ConversationInput input) {
    synchronized(traces){var trace=traces.get(input.inputId());if(trace!=null){
      traces.put(input.inputId(),new Trace(trace.inputId(),trace.original(),trace.correctionInput(),trace.candidate(),trace.adopted(),input.text(),trace.reason(),trace.atNanos()));
      events.publish(VoiceEventPublisher.Type.CORRECTION_TIMING,"agent_admission_additional_ms="+elapsed(trace.atNanos()));
    }}
  }
  private final class Job {
    final ConversationInput input;
    final String original;
    final BooleanSupplier current;
    final CompletableFuture<VoiceCorrectionValidator.Decision> result=new CompletableFuture<>();
    final long queuedAt=ticks.getAsLong();
    volatile long enqueuedAt;
    volatile FutureTask<Void> task;
    volatile ScheduledFuture<?> timeout;
    volatile String candidate;
    final java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
    Job(ConversationInput input,String original,BooleanSupplier current){this.input=input;this.original=original;this.current=current;}
    boolean finish(VoiceCorrectionValidator.Decision decision) {
      // Select one winner before recording diagnostics. Observers recheck the voice generation.
      if(!finished.compareAndSet(false,true))return false;
      trace(this,decision);result.complete(decision);
      jobs.remove(input.inputId(),this);
      var deadline=timeout;if(deadline!=null)deadline.cancel(false);
      events.publish(VoiceEventPublisher.Type.CORRECTION_RESULT,decision.reason()+" additional_ms="+elapsed(queuedAt));
      return true;
    }
    boolean cancel() {
      if(!finish(validator.fallback(input.text(),"cancelled")))return false;
      var pending=task;if(pending!=null){pending.cancel(true);workers.remove(pending);}
      return true;
    }
  }
  public VoiceCorrectionService(VoiceCorrectionProperties properties,Function<ConversationInput,VoiceCorrectionContext.Snapshot> contexts,
      Llm llm,VoiceEventPublisher events) {
    this(properties,contexts,llm,events,Executors.newSingleThreadScheduledExecutor(r->daemon(r,"voice-correction-deadline")),System::nanoTime);
  }
  VoiceCorrectionService(VoiceCorrectionProperties properties,Function<ConversationInput,VoiceCorrectionContext.Snapshot> contexts,
      Llm llm,VoiceEventPublisher events,ScheduledExecutorService deadlines,LongSupplier ticks) {
    properties.validate();this.properties=properties;this.contexts=contexts;this.llm=llm;this.events=events;this.deadlines=deadlines;this.ticks=ticks;
    workers=new ThreadPoolExecutor(properties.getMaxConcurrentRequests(),properties.getMaxConcurrentRequests(),0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(properties.getMaxQueuedRequests()),r->daemon(r,"voice-correction"),new ThreadPoolExecutor.AbortPolicy());
  }
  private static Thread daemon(Runnable work,String name){var thread=new Thread(work,name);thread.setDaemon(true);return thread;}
  private long elapsed(long start){return Math.max(0,(ticks.getAsLong()-start)/1_000_000);}
  public CompletionStage<VoiceCorrectionValidator.Decision> correct(ConversationInput input,BooleanSupplier current) {
    return correct(input,current,null);
  }
  public CompletionStage<VoiceCorrectionValidator.Decision> correct(ConversationInput input,BooleanSupplier current,
      Consumer<VoiceCorrectionValidator.Decision> completion) {
    return correct(input,input.text(),current,completion);
  }
  public CompletionStage<VoiceCorrectionValidator.Decision> correct(ConversationInput input,String asrOriginal,BooleanSupplier current,
      Consumer<VoiceCorrectionValidator.Decision> completion) {
    return correctInternal(input,Objects.requireNonNull(asrOriginal),current,completion);
  }
  private CompletionStage<VoiceCorrectionValidator.Decision> immediate(VoiceCorrectionValidator.Decision decision,Consumer<VoiceCorrectionValidator.Decision> completion) {
    if(completion!=null)completion.accept(decision);return CompletableFuture.completedFuture(decision);
  }
  private CompletionStage<VoiceCorrectionValidator.Decision> correctInternal(ConversationInput input,String asrOriginal,BooleanSupplier current,
      Consumer<VoiceCorrectionValidator.Decision> completion) {
    String raw=input.text();
    if(!properties.isEnabled() || VoiceCorrectionValidator.controlOrReply(raw))
      return immediate(new VoiceCorrectionValidator.Decision(raw,"bypassed",false),completion);
    if(closed || !current.getAsBoolean())return immediate(validator.fallback(raw,"cancelled"),completion);
    if(raw.codePointCount(0,raw.length())>properties.getMaxInputChars())return immediate(validator.fallback(raw,"input_limit"),completion);
    // Sending redacted ASR could change meaning; keep this utterance local instead.
    if(!CredentialRedactor.redact(raw).equals(raw))return immediate(validator.fallback(raw,"sensitive_input"),completion);
    var job=new Job(input,asrOriginal,current);
    var prior=jobs.putIfAbsent(input.inputId(),job);
    if(prior!=null) {
      if(!prior.input.equals(input))throw new IllegalArgumentException("Conflicting correction identity");
      if(completion!=null)prior.result.thenAccept(completion);
      return prior.result;
    }
    if(completion!=null)job.result.thenAccept(completion);
    try {
      job.timeout=deadlines.schedule(()-> {
        if(!job.finish(validator.fallback(raw,"timeout")))return;
        var pending=job.task;if(pending!=null){pending.cancel(true);workers.remove(pending);}
      },properties.getTimeoutMs(),TimeUnit.MILLISECONDS);
      // Snapshot before entering the executor queue; no worker ThreadLocal is consulted.
      var context=contexts.apply(input);
      job.enqueuedAt=ticks.getAsLong();
      job.task=new FutureTask<>(()-> {
        if(job.result.isDone())return null;
        if(!current.getAsBoolean()){job.cancel();return null;}
        long started=ticks.getAsLong();
        events.publish(VoiceEventPublisher.Type.CORRECTION_TIMING,"queue_ms="+elapsed(job.enqueuedAt)+" context_ms="+Math.max(0,(job.enqueuedAt-job.queuedAt)/1_000_000));
        try {
          String candidate=llm.call(raw,context);
          if(!job.finished.get() && candidate!=null && candidate.length()<=Math.min(65536,properties.getMaxInputChars()*16+2048))job.candidate=candidate;
          long validation=ticks.getAsLong();
          var decision=validator.validate(raw,candidate,properties.getMaxInputChars());
          events.publish(VoiceEventPublisher.Type.CORRECTION_TIMING,"llm_ms="+Math.max(0,(validation-started)/1_000_000)+" validation_ms="+elapsed(validation));
          if(current.getAsBoolean())job.finish(decision);else job.cancel();
        }catch(Exception failure){job.finish(validator.fallback(raw,"llm_failure"));} // Never publish exception text.
        return null;
      });
      if(!job.result.isDone())workers.execute(job.task);
    }catch(RejectedExecutionException full){job.finish(validator.fallback(raw,"queue_full"));}
    catch(RuntimeException unavailable){job.finish(validator.fallback(raw,"context_failure"));}
    return job.result;
  }
  public void cancelAll(){for(var job:List.copyOf(jobs.values()))job.cancel();}
  public List<ConversationInput> pending() {
    return jobs.values().stream().filter(job->!job.finished.get() && job.current.getAsBoolean())
        .sorted(java.util.Comparator.comparingLong(job->job.queuedAt)).map(job->job.input).toList();
  }
  public boolean cancel(UUID id) { var job=jobs.get(id);return job!=null && job.cancel(); }
  @Override public void close(){closed=true;cancelAll();deadlines.shutdownNow();workers.shutdownNow();}
}
