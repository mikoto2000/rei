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
  private static final Set<String> PROTECTED_FIELDS =
      Set.of(
          "model",
          "input",
          "instructions",
          "tools",
          "tool_choice",
          "store",
          "previous_response_id",
          "conversation",
          "max_tokens",
          "max_completion_tokens",
          "max_output_tokens",
          "reasoning",
          "text",
          "stream",
          "include",
          "parallel_tool_calls",
          "response_format",
          "messages",
          "functions",
          "function_call",
          "chat_template_kwargs",
          "background",
          "context_management",
          "truncation",
          "temperature",
          "top_p",
          "verbosity");

  private final ResolvedLlmEndpoint endpoint;
  private final OpenAiResponsesChatModel delegate;

  static ResponsesAdapter create(
      ResolvedLlmEndpoint endpoint,
      OpenAiResponsesChatOptions options,
      java.util.List<org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>
          customizers) {
    if (endpoint.apiType() != LlmApiType.RESPONSES
        || !Objects.equals(endpoint.baseUrl(), options.getBaseUrl())
        || !Objects.equals(endpoint.model(), options.getModel())
        || !Objects.equals(endpoint.maxOutputTokens(), options.getMaxOutputTokens())
        || !Objects.equals(endpoint.temperature(), options.getTemperature()))
      throw new IllegalArgumentException("Responses client must match the resolved endpoint");
    if (options.getMaxRetries() != 0)
      throw new IllegalArgumentException(
          "Responses SDK retries must stay zero at client construction");
    options = options.mutate().timeout(endpoint.readTimeout()).build();
    var transportCustomizers =
        new java.util.ArrayList<
            org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>();
    transportCustomizers.add(
        builder -> builder.interceptor(new ChatStreamTimeoutInterceptor(endpoint.readTimeout())));
    transportCustomizers.addAll(customizers);
    var client =
        org.springframework.ai.openai.setup.OpenAiSetup.setupAsyncClient(
            options.getBaseUrl(),
            options.getApiKey(),
            options.getCredential(),
            options.getMicrosoftDeploymentName(),
            options.getMicrosoftFoundryServiceVersion(),
            options.getOrganizationId(),
            options.isMicrosoftFoundry(),
            false,
            options.getModel(),
            options.getTimeout() == null ? endpoint.readTimeout() : options.getTimeout(),
            0,
            options.getProxy(),
            options.getCustomHeaders(),
            io.micrometer.observation.ObservationRegistry.NOOP,
            null,
            transportCustomizers);
    var model =
        OpenAiResponsesChatModel.builder()
            .options(options)
            .openAiClient(ResponsesSdkBoundary.safe(org.springframework.ai.openai.setup.OpenAiSetup.setupSyncClient(
                options.getBaseUrl(), options.getApiKey(), options.getCredential(),
                options.getMicrosoftDeploymentName(), options.getMicrosoftFoundryServiceVersion(),
                options.getOrganizationId(), options.isMicrosoftFoundry(), false, options.getModel(),
                options.getTimeout(), 0, options.getProxy(), options.getCustomHeaders(),
                io.micrometer.observation.ObservationRegistry.NOOP, null, transportCustomizers)))
            .openAiClientAsync(ResponsesSdkBoundary.safe(client))
            .httpClientBuilderCustomizers(transportCustomizers)
            .build();
    return new ResponsesAdapter(endpoint, model);
  }

  private ResponsesAdapter(ResolvedLlmEndpoint endpoint, OpenAiResponsesChatModel delegate) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (endpoint.apiType() != LlmApiType.RESPONSES)
      throw new IllegalArgumentException("Responses endpoint required");
  }

  @Override
  public OpenAiResponsesChatOptions options(ResolvedLlmEndpoint endpoint) {
    if (!this.endpoint.equals(endpoint))
      throw new IllegalArgumentException("Responses options must match the bound endpoint");
    if (endpoint.apiType() != LlmApiType.RESPONSES)
      throw new IllegalArgumentException("Responses endpoint required");
    return delegate
        .getOptions()
        .mutate()
        .model(endpoint.model())
        .maxOutputTokens(endpoint.maxOutputTokens())
        .temperature(endpoint.temperature())
        .maxRetries(0)
        .build();
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
    requireCapability(options.getVerbosity(), LlmCapabilities.Setting.TEXT_VERBOSITY, "verbosity");
    var defaults = delegate.getOptions();
    if (!Objects.equals(defaults.getBaseUrl(), options.getBaseUrl())
        || !Objects.equals(defaults.getApiKey(), options.getApiKey())
        || !Objects.equals(defaults.getCredential(), options.getCredential())
        || !Objects.equals(defaults.getOrganizationId(), options.getOrganizationId())
        || !Objects.equals(defaults.getCustomHeaders(), options.getCustomHeaders()))
      throw new IllegalArgumentException("Responses connection differs from the bound endpoint");
    if (!Objects.equals(endpoint.model(), options.getModel()))
      throw new IllegalArgumentException("Responses model differs from the bound endpoint");
    if (options.getMaxRetries() != 0)
      throw new IllegalArgumentException("Responses SDK retries must stay zero");
    if (options.getMaxOutputTokens() == null
        || options.getMaxOutputTokens() < 1
        || (endpoint.maxOutputTokens() != null
            && options.getMaxOutputTokens() > endpoint.maxOutputTokens()))
      throw new IllegalArgumentException("Responses output limit must respect the resolved bound");
    requireCapability(options.getTemperature(), LlmCapabilities.Setting.TEMPERATURE, "temperature");
    requireCapability(options.getTopP(), LlmCapabilities.Setting.TOP_P, "topP");
    requireCapability(
        options.getReasoningEffort(), LlmCapabilities.Setting.REASONING_EFFORT, "reasoningEffort");
    requireCapability(
        options.getReasoningSummary(),
        LlmCapabilities.Setting.REASONING_SUMMARY,
        "reasoningSummary");
    requireCapability(
        options.getParallelToolCalls(),
        LlmCapabilities.Setting.PARALLEL_TOOL_CALLS,
        "parallelToolCalls");
    requireCapability(
        options.getResponseFormat(), LlmCapabilities.Setting.STRUCTURED_OUTPUT, "structuredOutput");
    for (var message : prompt.getInstructions())
      if (message instanceof org.springframework.ai.chat.messages.UserMessage user
          && !user.getMedia().isEmpty())
        requireCapability(Boolean.TRUE, LlmCapabilities.Setting.IMAGE_INPUT, "imageInput");
    if (options.getHostedTools() != null && !options.getHostedTools().isEmpty())
      throw new IllegalArgumentException("Responses hostedTools are not supported");
    if (options.getTruncation() != null && !"disabled".equals(options.getTruncation()))
      throw new IllegalArgumentException("Responses truncation must stay disabled");
    if (options.getMaxToolCalls() != null)
      throw new IllegalArgumentException("Responses maxToolCalls requires hosted execution");
    if (Boolean.TRUE.equals(options.getStrict()))
      throw new IllegalArgumentException("Responses function strict must stay false");
    var extra = options.getExtraBody();
    if (extra != null)
      for (String field : extra.keySet())
        if (PROTECTED_FIELDS.contains(field))
          throw new IllegalArgumentException("Protected Responses field: " + field);
    var callbacks = options.getToolCallbacks();
    if (callbacks == null || callbacks.isEmpty())
      options = options.mutate().toolChoice(null).build();
    else {
      validateTools(callbacks);
      validateToolChoice(options.getToolChoice(), callbacks);
    }
    return new Prompt(prompt.getInstructions(), options);
  }

  private static void validateToolChoice(Object choice, java.util.List<org.springframework.ai.tool.ToolCallback> callbacks) {
    if (choice == null || (choice instanceof String value && Set.of("auto", "none", "required").contains(value))) return;
    String name = null;
    if (choice instanceof String value) {
      try {
        var node = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        if (node != null && "function".equals(node.path("type").asText()))
          name = node.has("name") ? node.path("name").asText() : node.path("function").path("name").asText();
      } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
        throw new IllegalArgumentException("Invalid Responses tool choice");
      }
    }
    if (name != null)
      for (var callback : callbacks)
        if (name.equals(callback.getToolDefinition().name())) return;
    throw new IllegalArgumentException("Invalid Responses tool choice");
  }

  private void validateTools(java.util.List<org.springframework.ai.tool.ToolCallback> callbacks) {
    int maximum =
        endpoint.capabilities().maxTools() == null ? 128 : endpoint.capabilities().maxTools();
    if (callbacks.size() > maximum)
      throw new IllegalArgumentException("Responses tool count exceeds endpoint limit");
    var names = new java.util.HashSet<String>();
    var mapper =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    for (var callback : callbacks) {
      var definition = callback.getToolDefinition();
      if (!definition.name().matches("[A-Za-z0-9_-]{1,64}") || !names.add(definition.name()))
        throw new IllegalArgumentException("Responses tool names must be valid and unique");
      try {
        var schema = mapper.readTree(definition.inputSchema());
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText()))
          throw new IllegalArgumentException("Responses tool schema must describe an object");
      } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
        throw new IllegalArgumentException("Responses tool schema must be valid JSON");
      }
    }
  }

  private void requireCapability(Object value, LlmCapabilities.Setting setting, String name) {
    if (value != null && !endpoint.capabilities().supportedSettings().contains(setting))
      throw new IllegalArgumentException("Responses endpoint does not support " + name);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    var request = prepare(prompt);
    try {
      var response = delegate.call(request);
      requireCompleted(response);
      return response;
    } catch (RuntimeException error) {
      throw ResponsesTransportException.normalize(error);
    }
  }

  private static void requireCompleted(ChatResponse response) {
    if (!"completed"
        .equals(
            response
                .getMetadata()
                .get(org.springframework.ai.openai.responses.OpenAiResponsesMetadata.STATUS)))
      throw new ResponsesTransportException("incomplete".equals(response.getMetadata().get(
          org.springframework.ai.openai.responses.OpenAiResponsesMetadata.STATUS))
          ? ("max_output_tokens".equals(response.getMetadata().get(
              org.springframework.ai.openai.responses.OpenAiResponsesMetadata.INCOMPLETE_REASON))
              ? ResponsesTransportException.Category.OUTPUT_LIMIT : ResponsesTransportException.Category.INCOMPLETE)
          : ResponsesTransportException.Category.INVALID_RESPONSE,
          ResponsesTransportException.usage(response.getMetadata().getUsage()));
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(
            () -> {
              var completed = new java.util.concurrent.atomic.AtomicBoolean();
              return delegate.stream(prepare(prompt))
                  .doOnNext(response -> {
                    if (response
                        .getMetadata()
                        .containsKey(
                            org.springframework.ai.openai.responses.OpenAiResponsesMetadata
                                .STATUS)) {
                      requireCompleted(response);
                      completed.set(true);
                    }
                  })
                  .concatWith(
                      Flux.defer(() ->
                          completed.get()
                              ? Flux.empty()
                              : Flux.error(
                                  new IllegalStateException(
                                      "Responses stream ended without completion"))));
            })
        .onErrorMap(ResponsesTransportException::normalize);
  }
}
