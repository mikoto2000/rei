package dev.mikoto2000.rei.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Test-only accounting wrapper; execution remains with the existing model and ToolLoop. */
final class ResponsesAcceptanceMeter implements ChatModel {
  private final ChatModel delegate;
  private final ResponsesAcceptanceBudget budget;
  private final boolean defaultTierOnly;

  ResponsesAcceptanceMeter(ChatModel delegate, ResponsesAcceptanceBudget budget) {
    this(delegate, budget, false);
  }

  ResponsesAcceptanceMeter(ChatModel delegate, ResponsesAcceptanceBudget budget, boolean defaultTierOnly) {
    this.delegate = delegate;
    this.budget = budget;
    this.defaultTierOnly = defaultTierOnly;
  }

  private Prompt priced(Prompt prompt) {
    if (!defaultTierOnly) return prompt;
    if (!(prompt.getOptions() instanceof org.springframework.ai.openai.responses.OpenAiResponsesChatOptions options))
      throw new IllegalArgumentException("Explicit native acceptance options required");
    if (options.getServiceTier() != null && !options.getServiceTier().equals("default"))
      throw new IllegalArgumentException("Acceptance requires the approved default service tier");
    return new Prompt(prompt.getInstructions(), options.mutate().serviceTier("default").build());
  }

  private ResponsesAcceptanceBudget.Slot reserve(Prompt prompt) {
    long bytes = 0;
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    for (var message : prompt.getInstructions())
      bytes = Math.addExact(bytes, codec.encode(message).length);
    var options = prompt.getOptions();
    if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
        && tools.getToolCallbacks() != null) {
      for (var callback : tools.getToolCallbacks()) {
        var definition = callback.getToolDefinition();
        bytes =
            Math.addExact(
                bytes,
                (definition.name() + definition.description() + definition.inputSchema())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length);
      }
    }
    long upper = Math.addExact(Math.multiplyExact(bytes, 4), 8192);
    if (!(options
            instanceof
            org.springframework.ai.openai.responses.OpenAiResponsesChatOptions nativeOptions)
        || nativeOptions.getMaxOutputTokens() == null)
      throw new IllegalStateException("Explicit native output bound required");
    return budget.reserve(upper, nativeOptions.getMaxOutputTokens());
  }

  private void complete(
      ResponsesAcceptanceBudget.Slot slot, dev.mikoto2000.rei.modelmessage.ModelTokenUsage usage) {
    if (usage == null || usage.input() == null || usage.output() == null || usage.total() == null) {
      budget.unknown(slot);
      throw new IllegalStateException("Provider usage unavailable; initial reservation retained");
    }
    budget.settle(slot, usage);
  }

  public ChatResponse call(Prompt prompt) {
    prompt = priced(prompt);
    var slot = reserve(prompt);
    ChatResponse response;
    try {
      response = delegate.call(prompt);
    } catch (RuntimeException failure) {
      budget.unknown(slot);
      throw failure;
    }
    complete(slot, dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(response));
    return response;
  }

  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> {
      var accepted = priced(prompt);
      var slot = reserve(accepted);
      var finalUsage =
          new java.util.concurrent.atomic.AtomicReference<
              dev.mikoto2000.rei.modelmessage.ModelTokenUsage>();
      var completed = new java.util.concurrent.atomic.AtomicBoolean();
      Flux<ChatResponse> stream;
      try {
        stream = delegate.stream(accepted);
      } catch (RuntimeException failure) {
        budget.unknown(slot);
        throw failure;
      }
      return stream
          .doOnNext(response ->
              finalUsage.set(dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(response)))
          .doOnComplete(() -> {
            if (completed.compareAndSet(false, true)) complete(slot, finalUsage.get());
          })
          .doFinally(signal -> {
            if (completed.compareAndSet(false, true)) budget.unknown(slot);
          });
    });
  }

  public ChatOptions getOptions() {
    return delegate.getOptions();
  }
}
