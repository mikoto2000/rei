package dev.mikoto2000.rei.llm;

import java.util.UUID;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import reactor.core.publisher.Flux;

/** Publishes lifecycle events for text LLM invocations except background activity detection. */
final class AgentEventChatModel implements ChatModel, dev.mikoto2000.rei.timing.TimingAwareModel {
  public dev.mikoto2000.rei.timing.TimingExecution timing(){return timing;}

  private final String feature;
  private final ChatModel delegate;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;

  private final dev.mikoto2000.rei.timing.TimingExecution timing;
  AgentEventChatModel(String feature, ChatModel delegate, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this(feature,delegate,eventFactory,eventPublisher,null);
  }
  AgentEventChatModel(String feature, ChatModel delegate, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher, dev.mikoto2000.rei.timing.TimingExecution timing) {
    this.timing=timing;
    this.feature = feature;
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    // Activity polling must not produce Shell or Web API notifications.
    if (LlmFeature.ACTIVITY.equals(feature)) return delegate.call(prompt);
    String requestId = UUID.randomUUID().toString();
    var measured=measurement(prompt,requestId);
    long startedAtNanos = System.nanoTime();
    publisher(prompt).publish(eventFactory.llmRequestStarted(null, requestId, feature));
    try {
      ChatResponse response = delegate.call(prompt);
      publisher(prompt).publish(eventFactory.llmResponseCompleted(null, requestId, elapsedMillis(startedAtNanos)));
      observeUsage(measured,response);
      if(measured!=null)measured.finish(dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS);
      return response;
    } catch (RuntimeException e) {
      if(measured!=null)measured.finish(dev.mikoto2000.rei.timing.TimingExecution.status(e));
      publisher(prompt).publish(eventFactory.llmRequestFailed(null, requestId, elapsedMillis(startedAtNanos),
          e));
      throw e;
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    if (LlmFeature.ACTIVITY.equals(feature)) return Flux.defer(() -> delegate.stream(prompt));
    return Flux.deferContextual(context -> {
      String requestId = UUID.randomUUID().toString();
      dev.mikoto2000.rei.timing.TimingAttempt trace=context.getOrDefault(dev.mikoto2000.rei.timing.TimingAttempt.KEY,null);
      var measured=measurement(prompt,requestId,trace);
    long startedAtNanos = System.nanoTime();
      java.util.concurrent.atomic.AtomicBoolean firstTokenPublished = new java.util.concurrent.atomic.AtomicBoolean();
      publisher(prompt).publish(eventFactory.llmRequestStarted(null, requestId, feature));
      return Flux.defer(() -> delegate.stream(prompt))
          .doOnNext(response -> {
            observeChunk(prompt,measured,response);
            if (firstTokenPublished.compareAndSet(false, true)) {
              publisher(prompt).publish(eventFactory.llmResponseFirstToken(null, requestId, elapsedMillis(startedAtNanos)));
            }
          })
          .doOnComplete(() -> {
            if(measured!=null)measured.finish(dev.mikoto2000.rei.timing.TimingRecorder.Status.SUCCESS);
            publisher(prompt).publish(eventFactory.llmResponseCompleted(null, requestId, elapsedMillis(startedAtNanos)));
          }).doOnError(error -> {
            if(measured!=null)measured.finish(dev.mikoto2000.rei.timing.TimingExecution.status(error));
            publisher(prompt).publish(eventFactory.llmRequestFailed(null, requestId, elapsedMillis(startedAtNanos), error));
          }).doOnCancel(() -> {
            if(measured!=null)measured.finish(cancelled(prompt)?dev.mikoto2000.rei.timing.TimingRecorder.Status.CANCELLED:dev.mikoto2000.rei.timing.TimingRecorder.Status.DISCONNECTED);
          });
    });
  }

  @Override
  public ChatOptions getOptions() {
    return delegate.getOptions();
  }

  private dev.mikoto2000.rei.core.chat.AgentRunContext owner(Prompt prompt) {
    if(prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options && options.getToolContext()!=null) {
      var values=options.getToolContext();
      if(values.get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY) instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext run)return run.runContext();
      if(values.get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName()) instanceof dev.mikoto2000.rei.core.chat.AgentRunContext run)return run;
    }
    return null;
  }
  private boolean cancelled(Prompt prompt) {
    return Thread.currentThread().isInterrupted() || prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options && options.getToolContext()!=null
      && options.getToolContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY) instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext run && run.isCancelled();
  }
  private dev.mikoto2000.rei.timing.TimingExecution.SpanLease measurement(Prompt prompt,String request) {
    if(timing==null||!timing.enabled())return null;
    var owner=owner(prompt);if(owner==null)return null;
    return measurement(prompt,request,null);
  }
  private dev.mikoto2000.rei.timing.TimingExecution.SpanLease measurement(Prompt prompt,String request,dev.mikoto2000.rei.timing.TimingAttempt trace) {
    if(timing==null||!timing.enabled())return null;
    var owner=owner(prompt);if(owner==null)return null;
    if(trace!=null)trace.physicalSpan().set(request);
    return timing.startSpanWithId(owner.runId(),request,owner.runId(),trace==null?request:trace.requestId(),trace==null?request:Integer.toString(trace.number()),dev.mikoto2000.rei.timing.TimingRecorder.Category.LLM);
  }
  private void observeChunk(Prompt prompt,dev.mikoto2000.rei.timing.TimingExecution.SpanLease measured,ChatResponse response) {
    if(measured==null)return;
    timing.safely(()->{
      measured.mark(dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_FRAMEWORK_CHUNK);
      var owner=owner(prompt);if(owner!=null)timing.markRun(owner.runId(),dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_FRAMEWORK_CHUNK);
      if(response!=null&&response.getResult()!=null&&response.getResult().getOutput()!=null&&response.getResult().getOutput().getText()!=null&&!response.getResult().getOutput().getText().isEmpty()) {
        measured.mark(dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_GENERATION_TEXT);
        if(owner!=null)timing.markRun(owner.runId(),dev.mikoto2000.rei.timing.TimingRecorder.Metric.FIRST_GENERATION_TEXT);
      }
      observeUsage(measured,response);
    });
  }
  private void observeUsage(dev.mikoto2000.rei.timing.TimingExecution.SpanLease measured,ChatResponse response) {
    if(measured==null)return;
    timing.safely(()->{
      if(response!=null&&response.getMetadata()!=null&&response.getMetadata().getUsage()!=null
          && !(response.getMetadata().getUsage() instanceof org.springframework.ai.chat.metadata.EmptyUsage)) {
        var usage=response.getMetadata().getUsage();
        measured.usage(usage.getPromptTokens()==null?null:usage.getPromptTokens().longValue(),usage.getCompletionTokens()==null?null:usage.getCompletionTokens().longValue(),null,null);
      }
    });
  }
  private AgentEventPublisher publisher(Prompt prompt) {
    dev.mikoto2000.rei.core.chat.AgentRunContext owner = null;
    if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
            instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution) owner = execution.runContext();
    var captured = owner;
    if (captured == null && prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName())
            instanceof dev.mikoto2000.rei.core.chat.AgentRunContext context) {
      return event -> eventPublisher.publish(event.withOwnership(context));
    }
    return event -> eventPublisher.publish(event.withOwnership(captured));
  }
  private long elapsedMillis(long startedAtNanos) {
    return (System.nanoTime() - startedAtNanos) / 1_000_000L;
  }
}
