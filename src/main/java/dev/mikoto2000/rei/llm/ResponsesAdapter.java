package dev.mikoto2000.rei.llm;

import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import reactor.core.publisher.Flux;

/** Responses wire boundary. Not registered for production before durable integration. */
final class ResponsesAdapter implements LlmProtocolAdapter, ChatModel {
  private static final Set<String> PROTECTED_FIELDS = Set.of(
      "model", "input", "instructions", "tools", "tool_choice", "store", "previous_response_id",
      "conversation", "max_tokens", "max_completion_tokens", "max_output_tokens", "reasoning",
      "text", "stream", "include", "parallel_tool_calls", "response_format", "messages",
      "functions", "function_call", "chat_template_kwargs", "background", "context_management",
      "truncation");

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
    var extra = options.getExtraBody();
    if (extra != null)
      for (String field : extra.keySet())
        if (PROTECTED_FIELDS.contains(field))
          throw new IllegalArgumentException("Protected Responses field: " + field);
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
