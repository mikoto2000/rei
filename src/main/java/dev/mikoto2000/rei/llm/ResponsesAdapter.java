package dev.mikoto2000.rei.llm;

import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import reactor.core.publisher.Flux;

/** Responses wire boundary. Not registered for production before durable integration. */
final class ResponsesAdapter implements LlmProtocolAdapter, ChatModel {
  private final ResolvedLlmEndpoint endpoint;
  private final OpenAiResponsesChatModel delegate;

  ResponsesAdapter(ResolvedLlmEndpoint endpoint, OpenAiResponsesChatModel delegate) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (endpoint.apiType() != LlmApiType.RESPONSES)
      throw new IllegalArgumentException("Responses endpoint required");
  }

  @Override
  public OpenAiResponsesChatOptions options(ResolvedLlmEndpoint endpoint) {
    if (endpoint.apiType() != LlmApiType.RESPONSES)
      throw new IllegalArgumentException("Responses endpoint required");
    return delegate.getOptions().mutate().model(endpoint.model())
        .maxOutputTokens(endpoint.maxOutputTokens()).temperature(endpoint.temperature())
        .maxRetries(0).build();
  }

  @Override
  public OpenAiResponsesChatOptions getOptions() {
    return options(endpoint);
  }

  private Prompt prepare(Prompt prompt) {
    var supplied = prompt.getOptions();
    if (supplied != null && !(supplied instanceof OpenAiResponsesChatOptions))
      throw new IllegalArgumentException("Responses native options required");
    var options = supplied == null ? getOptions() : (OpenAiResponsesChatOptions) supplied;
    var callbacks = options.getToolCallbacks();
    if (callbacks == null || callbacks.isEmpty())
      options = options.mutate().toolChoice(null).build();
    return new Prompt(prompt.getInstructions(), options);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return delegate.call(prepare(prompt));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return delegate.stream(prepare(prompt));
  }
}
