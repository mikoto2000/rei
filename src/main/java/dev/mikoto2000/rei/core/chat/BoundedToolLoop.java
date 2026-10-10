package dev.mikoto2000.rei.core.chat;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.*;
import dev.mikoto2000.rei.llm.OutputLimitDetector;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Ephemeral explicit loop: no advisors, persistence, planner, or ambient tool resolution. */
public final class BoundedToolLoop {
  private final ToolLoopSupport tools = new ToolLoopSupport();
  public static final class SharedBudgetExceeded extends RuntimeException { }
  public static final class MaxStepsExceeded extends RuntimeException { }
  private static boolean isTransientModelFailure(Throwable error) {
    // Spring AI 2 streams SDK failures through async completion wrappers. Do not
    // follow arbitrary causes: an application failure may have a transient cause.
    while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null) {
      error = error.getCause();
    }
    if (error instanceof OpenAIIoException || error instanceof OpenAIRetryableException) return true;
    if (error instanceof OpenAIServiceException service) {
      String retryHint = service.headers().values("X-Should-Retry").stream().findFirst().orElse("");
      if ("false".equals(retryHint)) return false;
      if ("true".equals(retryHint)) return true;
      int status = service.statusCode();
      return status == 408 || status == 409 || status == 429 || status >= 500;
    }
    return false;
  }
  /** One invocation-wide retry allowance, also shared by repair cycles and the tool-free judge. */
  public static final class ModelRetries {
    private final AtomicInteger remaining;
    private final AtomicInteger attempts=new AtomicInteger();
    private final List<String> failures=new java.util.concurrent.CopyOnWriteArrayList<>();
    public ModelRetries(int max){if(max<0||max>3)throw new IllegalArgumentException("Model retries must be 0 to 3");remaining=new AtomicInteger(max);}
    private boolean reserve(){return remaining.getAndUpdate(value->Math.max(0,value-1))>0;}
    private void failure(Throwable error,boolean received){if(isTransientModelFailure(error)&&failures.size()<4)failures.add(received?"TRANSIENT_MODEL_FAILURE_AFTER_PARTIAL_RESPONSE":"TRANSIENT_MODEL_FAILURE_BEFORE_RESPONSE");}
    public int attempts(){return attempts.get();}
    public List<String> history(){return List.copyOf(failures);}
  }
  public record Outcome(String output, List<Message> history) {
    public Outcome { history = List.copyOf(history); }
  }
  public Mono<String> run(ChatModel model, Prompt prompt, int maxSteps, AgentRunContext owner, Runnable checkActive) {
    return Mono.defer(() -> runWithHistory(model, prompt, new AtomicInteger(maxSteps), owner, checkActive)).map(Outcome::output);
  }
  public Mono<Outcome> runWithHistory(ChatModel model, Prompt prompt, AtomicInteger remaining, AgentRunContext owner, Runnable checkActive) {
    return runWithHistory(model,prompt,remaining,owner,checkActive,null);
  }
  public Mono<Outcome> runWithHistory(ChatModel model,Prompt prompt,AtomicInteger remaining,AgentRunContext owner,Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation) {
    return runWithHistory(model,prompt,remaining,owner,checkActive,reservation,new ModelRetries(0));
  }
  public Mono<Outcome> runWithHistory(ChatModel model,Prompt prompt,AtomicInteger remaining,AgentRunContext owner,Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,ModelRetries retries){return iteration(model,prompt,remaining,owner,checkActive,reservation,retries);}
  private Mono<ChatResponse> response(ChatModel model,Prompt prompt,AtomicInteger remaining,Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,ModelRetries retries,boolean retrying,dev.mikoto2000.rei.timing.TimingAttempt previous) {
    return Mono.defer(() -> {
      checkActive.run();
      var timing=model instanceof dev.mikoto2000.rei.timing.TimingAwareModel observed?observed.timing():null;
      var trace=timing!=null&&timing.enabled()?(previous==null?new dev.mikoto2000.rei.timing.TimingAttempt(UUID.randomUUID().toString(),1,new AtomicReference<>()):previous):null;
      if (remaining.getAndDecrement() <= 0) return Mono.error(new MaxStepsExceeded());
      if(reservation!=null&&!reservation.tryReserve())return Mono.error(new SharedBudgetExceeded());
      checkActive.run();
      AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
      var started=new java.util.concurrent.atomic.AtomicBoolean();
      var reported=new java.util.concurrent.atomic.AtomicBoolean();
      var received=new java.util.concurrent.atomic.AtomicBoolean();
      return new MessageAggregator().aggregate(reactor.core.publisher.Flux.defer(()->{
        checkActive.run();started.set(true);if(retrying)retries.attempts.incrementAndGet();var stream=model.stream(prompt);return trace==null?stream:stream.contextWrite(context->context.put(dev.mikoto2000.rei.timing.TimingAttempt.KEY,trace));
      }).doOnNext(chunk->received.set(true)).doOnError(error->retries.failure(error,received.get())), aggregated::set).then(Mono.defer(() -> {
        checkActive.run();
        var response = aggregated.get();
        if (response == null || response.getResult() == null) return Mono.error(new IllegalStateException("Empty response"));
        if(reservation!=null) {
          reported.set(true);
          var usage=response.getMetadata().getUsage();
          reservation.recordTotalTokens(usage==null?null:usage.getTotalTokens());
        }
        if (OutputLimitDetector.isOutputLimitReached(response)) return Mono.error(new IllegalStateException("Output limit"));
        return Mono.just(response);
      })).doOnError(error->{
        if(reservation!=null&&reservation.tokenLimitEnabled()&&started.get()&&!reported.getAndSet(true))reservation.recordTotalTokens(null);
      }).doFinally(signal->{
        if(signal==reactor.core.publisher.SignalType.CANCEL&&reservation!=null&&reservation.tokenLimitEnabled()&&started.get()&&!reported.getAndSet(true)) {
          try{reservation.recordTotalTokens(null);}catch(RuntimeException stopped){/* Accounting state prevents further calls. */}
        }
      }).onErrorResume(error->{
        if(isTransientModelFailure(error)&&!received.get()) {
          checkActive.run();
          if(retries.reserve()) {
            var delay=Mono.delay(java.time.Duration.ofMillis(100));
            if(trace!=null && prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext()!=null) {
              var captured=options.getToolContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY);
              AgentRunContext owner=captured instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution?execution.runContext():
                  options.getToolContext().get(AgentRunContext.class.getName()) instanceof AgentRunContext context?context:null;
              if(owner!=null) {
                String parent=trace.physicalSpan().get();
                var span=timing.startSpan(owner.runId(),parent==null?owner.runId():parent,trace.requestId(),Integer.toString(trace.number()+1),dev.mikoto2000.rei.timing.TimingRecorder.Category.RETRY);
                delay=delay.doOnSuccess(value->span.finish(dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS)).doOnError(failure->span.finish(dev.mikoto2000.rei.timing.TimingExecution.status(failure))).doOnCancel(()->span.finish(dev.mikoto2000.rei.timing.TimingRecorder.Status.CANCELLED));
              }
            }
            return delay.then(response(model,prompt,remaining,checkActive,reservation,retries,true,trace==null?null:trace.next()));
          }
        }
        return Mono.error(error);
      });
    });
  }
  private Mono<Outcome> iteration(ChatModel model,Prompt prompt,AtomicInteger remaining,AgentRunContext owner,Runnable checkActive,
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation,ModelRetries retries) {
    return response(model,prompt,remaining,checkActive,reservation,retries,false,null).flatMap(response->Mono.defer(()->{
        checkActive.run();
        if (!response.hasToolCalls()) {
          var history = new ArrayList<Message>(prompt.getInstructions());
          history.add(response.getResult().getOutput());
          return Mono.just(new Outcome(Objects.toString(response.getResult().getOutput().getText(), ""), history));
        }
        var options = (ToolCallingChatOptions) prompt.getOptions();
        Set<String> allowed = new HashSet<>();
        options.getToolCallbacks().forEach(tool -> allowed.add(tool.getToolDefinition().name()));
        // Validate the entire batch before any side effect. Never let a resolver find ambient tools.
        for (var generation : response.getResults()) for (var call : generation.getOutput().getToolCalls()) {
          if (!allowed.contains(call.name())) return Mono.error(new IllegalArgumentException("Unrequested tool"));
        }
        return Mono.fromCallable(() -> {
          try (var scope = AgentRunScope.open(owner)) {
            checkActive.run();
            return tools.execute(prompt, response);
          }
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(result -> {
          checkActive.run();
          if (result.returnDirect()) {
            var output = ToolExecutionResult.buildGenerations(result).getFirst().getOutput();
            var history = new ArrayList<Message>(result.conversationHistory()); history.add(output);
            return Mono.just(new Outcome(output.getText(), history));
          }
          return iteration(model, new Prompt(result.conversationHistory(), prompt.getOptions()), remaining, owner, checkActive,reservation,retries);
        });
    }));
  }
}
