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
  ResponsesAcceptanceMeter(ChatModel delegate, ResponsesAcceptanceBudget budget) {
    this.delegate = delegate;
    this.budget = budget;
  }

  private ResponsesAcceptanceBudget.Slot reserve(Prompt prompt) {
    long bytes = 0;
    var codec = new dev.mikoto2000.rei.modelmessage.ModelMessageCodec();
    for (var message : prompt.getInstructions()) bytes = Math.addExact(bytes, codec.encode(message).length);
    var options = prompt.getOptions();
    if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
        && tools.getToolCallbacks() != null) {
      for (var callback : tools.getToolCallbacks()) {
        var definition = callback.getToolDefinition();
        bytes = Math.addExact(bytes, (definition.name() + definition.description()
            + definition.inputSchema()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      }
    }
    long upper = Math.addExact(Math.multiplyExact(bytes, 4), 8192);
    if (!(options instanceof org.springframework.ai.openai.responses.OpenAiResponsesChatOptions nativeOptions)
        || nativeOptions.getMaxOutputTokens() == null) throw new IllegalStateException("Explicit native output bound required");
    return budget.reserve(upper, nativeOptions.getMaxOutputTokens());
  }

  private void complete(ResponsesAcceptanceBudget.Slot slot,
      dev.mikoto2000.rei.modelmessage.ModelTokenUsage usage) {
    if (usage == null || usage.input() == null || usage.output() == null || usage.total() == null) {
      budget.unknown(slot);
      throw new IllegalStateException("Provider usage unavailable; initial reservation retained");
    }
    budget.settle(slot, usage);
  }

  public ChatResponse call(Prompt prompt) {
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
      var slot = reserve(prompt);
      var finalUsage = new java.util.concurrent.atomic.AtomicReference<dev.mikoto2000.rei.modelmessage.ModelTokenUsage>();
      var completed = new java.util.concurrent.atomic.AtomicBoolean();
      Flux<ChatResponse> stream;
      try {
        stream = delegate.stream(prompt);
      } catch (RuntimeException failure) {
        budget.unknown(slot);
        throw failure;
      }
      return stream.doOnNext(response -> finalUsage.set(dev.mikoto2000.rei.modelmessage.ModelTokenUsage.from(response)))
          .doOnComplete(() -> {
            if (completed.compareAndSet(false, true)) complete(slot, finalUsage.get());
          })
          .doFinally(signal -> {
            if (completed.compareAndSet(false, true)) budget.unknown(slot);
          });
    });
  }
  public ChatOptions getOptions() { return delegate.getOptions(); }
}
