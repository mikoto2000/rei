package dev.mikoto2000.rei.core.stagnation;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import dev.mikoto2000.rei.llm.OutputLimitDetector;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Explicit streaming LLM -> tool-result loop. Existing client advisors still own prompt/memory handling. */
public class StagnationChatModel implements ChatModel {
  private final ChatModel delegate;
  private final dev.mikoto2000.rei.core.chat.ToolLoopSupport tools = new dev.mikoto2000.rei.core.chat.ToolLoopSupport();

  public StagnationChatModel(ChatModel delegate) { this.delegate = delegate; }
  @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
  @Override public ChatResponse call(Prompt prompt) {
    if (context(prompt) == null) return delegate.call(prompt);
    AtomicReference<ChatResponse> result = new AtomicReference<>();
    new MessageAggregator().aggregate(stream(prompt), result::set).blockLast();
    return result.get();
  }
  @Override public Flux<ChatResponse> stream(Prompt prompt) {
    RunExecutionContext context = context(prompt);
    if (context == null) return delegate.stream(prompt);
    return Flux.defer(() -> iteration(prepare(prompt, context), context, true, context.progressVersion()));
  }

  private Flux<ChatResponse> iteration(Prompt prompt, RunExecutionContext context, boolean prepaid, long progressAtLimit) {
    return Flux.defer(() -> {
      context.checkActive();
      if (!prepaid) context.consumeNextLlmCall();
      context.beginIteration();
      AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
      Flux<ChatResponse> response = new MessageAggregator().aggregate(Flux.defer(() -> delegate.stream(prompt)), aggregate::set);
      return response.concatWith(Flux.defer(() -> {
        ChatResponse result = aggregate.get();
        if (result == null || result.getResult() == null) {
          context.endIteration();
          return Flux.empty();
        }
        var usage = result.getMetadata().getUsage();
        if (usage != null && usage.getCompletionTokens() != null) {
          context.recordCompletionTokens(usage.getCompletionTokens());
        }
        if (result.hasToolCalls() && !OutputLimitDetector.isOutputLimitReached(result)) {
          return Mono.fromCallable(() -> {
            context.checkActive();
            try { return tools.execute(prompt, result); }
            finally { context.endIteration(); }
          }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(toolResult -> {
            if (toolResult.returnDirect()) return Flux.just(new ChatResponse(ToolExecutionResult.buildGenerations(toolResult)));
            List<Message> messages = new ArrayList<>(toolResult.conversationHistory());
            messages.addAll(context.applyInterventions());
            String notice = context.requestReplan();
            if (notice != null) messages.add(new UserMessage(notice));
            return iteration(new Prompt(messages, prompt.getOptions()), context, false, progressAtLimit);
          });
        }
        context.endIteration();
        if (!OutputLimitDetector.isOutputLimitReached(result)) {
          var guidance = context.applyInterventions();
          if (guidance.isEmpty()) return Flux.empty();
          List<Message> messages = new ArrayList<>(prompt.getInstructions());
          messages.add(result.getResult().getOutput());
          messages.addAll(guidance);
          return iteration(new Prompt(messages, prompt.getOptions()), context, false, progressAtLimit);
        }
        String notice = context.requestReplan();
        if (notice != null || context.progressVersion() > progressAtLimit) {
          List<Message> messages = new ArrayList<>(prompt.getInstructions());
          // A length-truncated tool call has no complete arguments and must never be replayed/executed.
          messages.add(new AssistantMessage(Objects.toString(result.getResult().getOutput().getText(), "")));
          messages.add(new UserMessage(notice != null ? notice
              : "Continue the current goal from the verified progress. Do not repeat completed actions. Keep the next output concise."));
          return iteration(new Prompt(messages, prompt.getOptions()), context, false, context.progressVersion());
        }
        return Flux.empty(); // Outer output-limit splitter handles a goal that made no progress.
      })).doOnError(error -> context.endIteration()).doOnCancel(context::endIteration);
    });
  }

  private Prompt prepare(Prompt prompt, RunExecutionContext context) {
    ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions().copy();
    options.setInternalToolExecutionEnabled(false);
    List<ToolCallback> callbacks = new ArrayList<>();
    for (ToolCallback callback : options.getToolCallbacks()) callbacks.add(observe(callback, context));
    options.setToolCallbacks(callbacks);
    return new Prompt(prompt.getInstructions(), options);
  }

  private ToolCallback observe(ToolCallback delegateTool, RunExecutionContext context) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() { return delegateTool.getToolDefinition(); }
      public ToolMetadata getToolMetadata() { return delegateTool.getToolMetadata(); }
      public String call(String input) { return call(input, null); }
      public String call(String input, ToolContext toolContext) {
        try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(context.runContext())) {
        context.checkActive();
        String name = getToolDefinition().name();
        var before = context.evaluator().beforeTool(name, input);
        try {
          String result = toolContext == null ? delegateTool.call(input) : delegateTool.call(input, toolContext);
          context.recordTool(name, input, result, before);
          return result;
        } catch (RuntimeException error) {
          context.recordFailure(name, input, error);
          throw error;
        }
        }
      }
    };
  }

  private RunExecutionContext context(Prompt prompt) {
    if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null
        && options.getToolContext().get(RunExecutionContext.KEY) instanceof RunExecutionContext context) return context;
    return null;
  }
}
