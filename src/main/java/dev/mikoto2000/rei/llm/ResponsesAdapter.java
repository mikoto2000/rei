package dev.mikoto2000.rei.llm;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesMetadata;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.tool.ToolCallback;
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
  private final com.openai.client.OpenAIClient syncClient;
  private final com.openai.client.OpenAIClientAsync asyncClient;
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(64)
                          .maxStringLength(4_194_304)
                          .maxNumberLength(1000)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  static ResponsesAdapter create(
      ResolvedLlmEndpoint endpoint,
      OpenAiResponsesChatOptions options,
      List<OpenAiHttpClientBuilderCustomizer> customizers) {
    if (endpoint.apiType() != LlmApiType.RESPONSES
        || options.getDeploymentName() != null
        || options.isMicrosoftFoundry()
        || !Objects.equals(endpoint.baseUrl(), options.getBaseUrl())
        || !Objects.equals(endpoint.model(), options.getModel())
        || !Objects.equals(endpoint.maxOutputTokens(), options.getMaxOutputTokens())
        || !Objects.equals(endpoint.temperature(), options.getTemperature()))
      throw new IllegalArgumentException("Responses client must match the resolved endpoint");
    if (options.getMaxRetries() != 0)
      throw new IllegalArgumentException(
          "Responses SDK retries must stay zero at client construction");
    options = options.mutate().timeout(endpoint.readTimeout()).build();
    var transportCustomizers = new ArrayList<OpenAiHttpClientBuilderCustomizer>();
    transportCustomizers.add(builder ->
        builder.interceptor(chain ->
            chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .tag(
                        RequestReadTimeout.class, new RequestReadTimeout(chain.readTimeoutMillis()))
                    .build())));
    transportCustomizers.addAll(customizers);
    transportCustomizers.add(builder -> {
      builder
          .interceptor(chain -> {
            var request = chain.request();
            var body = request.body();
            if (body != null)
              request = request.newBuilder().method(request.method(), nonReplayable(body)).build();
            return chain.proceed(request);
          })
          .interceptor(chain -> {
            var requested = chain.request().tag(RequestReadTimeout.class);
            long bound = endpoint.readTimeout().toMillis();
            long millis =
                requested == null || requested.millis() == 0
                    ? bound
                    : Math.min(bound, requested.millis());
            return new ChatStreamTimeoutInterceptor(java.time.Duration.ofMillis(millis))
                .intercept(chain);
          });
    });
    var client =
        OpenAiSetup.setupAsyncClient(
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
            ObservationRegistry.NOOP,
            null,
            transportCustomizers);
    var syncClient =
        ResponsesSdkBoundary.safe(
            OpenAiSetup.setupSyncClient(
                options.getBaseUrl(),
                options.getApiKey(),
                options.getCredential(),
                options.getMicrosoftDeploymentName(),
                options.getMicrosoftFoundryServiceVersion(),
                options.getOrganizationId(),
                options.isMicrosoftFoundry(),
                false,
                options.getModel(),
                options.getTimeout(),
                0,
                options.getProxy(),
                options.getCustomHeaders(),
                ObservationRegistry.NOOP,
                null,
                transportCustomizers),
            endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.PLAINTEXT);
    var asyncClient = ResponsesSdkBoundary.safe(client,
        endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.PLAINTEXT);
    var model =
        OpenAiResponsesChatModel.builder()
            .options(options)
            .openAiClient(syncClient)
            .openAiClientAsync(asyncClient)
            .httpClientBuilderCustomizers(transportCustomizers)
            .build();
    return new ResponsesAdapter(endpoint, model, syncClient, asyncClient);
  }

  private record RequestReadTimeout(int millis) {}

  private static okhttp3.RequestBody nonReplayable(okhttp3.RequestBody body) {
    return new okhttp3.RequestBody() {
      @Override
      public okhttp3.MediaType contentType() {
        return body.contentType();
      }

      @Override
      public long contentLength() throws java.io.IOException {
        return body.contentLength();
      }

      @Override
      public boolean isOneShot() {
        return true;
      }

      @Override
      public boolean isDuplex() {
        return body.isDuplex();
      }

      @Override
      public void writeTo(okio.BufferedSink sink) throws java.io.IOException {
        body.writeTo(sink);
      }
    };
  }

  private ResponsesAdapter(
      ResolvedLlmEndpoint endpoint,
      OpenAiResponsesChatModel delegate,
      com.openai.client.OpenAIClient syncClient,
      com.openai.client.OpenAIClientAsync asyncClient) {
    this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.syncClient = Objects.requireNonNull(syncClient, "syncClient");
    this.asyncClient = Objects.requireNonNull(asyncClient, "asyncClient");
    if (endpoint.apiType() != LlmApiType.RESPONSES)
      throw new IllegalArgumentException("Responses endpoint required");
  }

  /** An explicit model shares the same resolved connection and SDK clients, never another API. */
  ResponsesAdapter forModel(String model) {
    if (model == null || model.equals(endpoint.model())) return this;
    if (model.isBlank()) throw new IllegalArgumentException("Responses model is required");
    var resolved =
        new ResolvedLlmEndpoint(
            endpoint.endpointId(),
            endpoint.generation(),
            endpoint.apiType(),
            endpoint.baseUrl(),
            endpoint.credentialRef(),
            model,
            endpoint.capabilities(),
            endpoint.maxOutputTokens(),
            endpoint.temperature(),
            endpoint.readTimeout());
    var options =
        getOptions()
            .mutate()
            .model(model)
            .toolContext(java.util.Map.of(ResolvedLlmEndpoint.CONTEXT_KEY, resolved))
            .build();
    var selected =
        OpenAiResponsesChatModel.builder()
            .options(options)
            .openAiClient(syncClient)
            .openAiClientAsync(asyncClient)
            .build();
    return new ResponsesAdapter(resolved, selected, syncClient, asyncClient);
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
    if (endpoint.capabilities().reasoningReplay() == ReasoningReplayMode.PLAINTEXT
        && (options.getInclude() != null && !options.getInclude().isEmpty()
            || options.getMetadata() != null && !options.getMetadata().isEmpty()
            || options.getServiceTier() != null
            || options.getPromptCacheKey() != null
            || options.getSafetyIdentifier() != null))
      throw new IllegalArgumentException("Responses plaintext platform setting is unsupported");
    requireCapability(options.getVerbosity(), LlmCapabilities.Setting.TEXT_VERBOSITY, "verbosity");
    var defaults = delegate.getOptions();
    var requestTimeout = options.getTimeout();
    if (!Objects.equals(defaults.getBaseUrl(), options.getBaseUrl())
        || !Objects.equals(defaults.getApiKey(), options.getApiKey())
        || !Objects.equals(defaults.getCredential(), options.getCredential())
        || !Objects.equals(defaults.getDeploymentName(), options.getDeploymentName())
        || !Objects.equals(
            defaults.getMicrosoftFoundryServiceVersion(),
            options.getMicrosoftFoundryServiceVersion())
        || defaults.isMicrosoftFoundry() != options.isMicrosoftFoundry()
        || !Objects.equals(defaults.getProxy(), options.getProxy())
        || (requestTimeout != null
            && (requestTimeout.compareTo(java.time.Duration.ofMillis(1)) < 0
                || requestTimeout.compareTo(endpoint.readTimeout()) > 0))
        || !Objects.equals(defaults.getOrganizationId(), options.getOrganizationId())
        || !Objects.equals(defaults.getCustomHeaders(), options.getCustomHeaders()))
      throw new IllegalArgumentException("Responses connection differs from the bound endpoint");
    if (!Objects.equals(endpoint.model(), options.getModel()))
      throw new IllegalArgumentException("Responses model differs from the bound endpoint");
    if (options.getMaxRetries() != 0)
      throw new IllegalArgumentException("Responses SDK retries must stay zero");
    var outputLimit = options.getMaxOutputTokens();
    var resolvedLimit = endpoint.maxOutputTokens();
    if (outputLimit == null
        || outputLimit < 1
        || (resolvedLimit != null && outputLimit > resolvedLimit))
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
      if (message instanceof UserMessage user && !user.getMedia().isEmpty())
        requireCapability(Boolean.TRUE, LlmCapabilities.Setting.IMAGE_INPUT, "imageInput");
    var hostedTools = options.getHostedTools();
    if (hostedTools != null && !hostedTools.isEmpty())
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
    if (extra != null && !extra.isEmpty())
      throw new IllegalArgumentException(
          "Responses extraBody extensions require an explicit typed contract");
    var callbacks = options.getToolCallbacks();
    if (callbacks == null || callbacks.isEmpty())
      options = options.mutate().toolChoice(null).build();
    else {
      validateTools(callbacks);
      validateToolChoice(options.getToolChoice(), callbacks);
    }
    return ResponsesReasoningReplay.prepare(
        new Prompt(prompt.getInstructions(), options), endpoint);
  }

  private static void validateToolChoice(Object choice, List<ToolCallback> callbacks) {
    if (choice == null
        || (choice instanceof String value && Set.of("auto", "none", "required").contains(value)))
      return;
    String name = null;
    if (choice instanceof String value) {
      try {
        if (value.length() > 4_194_304)
          throw new IllegalArgumentException("Invalid Responses tool choice");
        var node = JSON.readTree(value);
        if (node != null && "function".equals(node.path("type").asText()))
          name =
              node.has("name")
                  ? node.path("name").asText()
                  : node.path("function").path("name").asText();
      } catch (JsonProcessingException invalid) {
        throw new IllegalArgumentException("Invalid Responses tool choice");
      }
    }
    if (name != null)
      for (var callback : callbacks) if (name.equals(callback.getToolDefinition().name())) return;
    throw new IllegalArgumentException("Invalid Responses tool choice");
  }

  private void validateTools(List<ToolCallback> callbacks) {
    var configuredMaximum = endpoint.capabilities().maxTools();
    int maximum = configuredMaximum == null ? 128 : configuredMaximum;
    if (callbacks.size() > maximum)
      throw new IllegalArgumentException("Responses tool count exceeds endpoint limit");
    var names = new java.util.HashSet<String>();
    for (var callback : callbacks) {
      var definition = callback.getToolDefinition();
      if (!definition.name().matches("[A-Za-z0-9_-]{1,64}") || !names.add(definition.name()))
        throw new IllegalArgumentException("Responses tool names must be valid and unique");
      try {
        if (definition.inputSchema().length() > 4_194_304)
          throw new IllegalArgumentException("Responses tool schema exceeds the size limit");
        var schema = JSON.readTree(definition.inputSchema());
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText()))
          throw new IllegalArgumentException("Responses tool schema must describe an object");
      } catch (JsonProcessingException error) {
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
      if (OutputLimitDetector.isOutputLimitReached(response)) {
        var generations =
            response.getResults().stream()
                .map(generation ->
                    new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(
                            java.util.Objects.toString(generation.getOutput().getText(), "")),
                        generation.getMetadata()))
                .toList();
        return new ChatResponse(generations, response.getMetadata());
      }
      var bound = ResponsesReasoningReplay.bind(response, request, endpoint);
      validateComplete(bound, request);
      return bound;
    } catch (RuntimeException error) {
      throw ResponsesTransportException.normalize(error);
    }
  }

  private static void requireNoRefusal(ChatResponse response) {
    for (var generation : response.getResults()) {
      var refusal = generation.getMetadata().get(OpenAiResponsesMetadata.REFUSAL);
      if (refusal instanceof String text && !text.isBlank())
        throw new ResponsesTransportException(
            ResponsesTransportException.Category.REFUSAL,
            ResponsesTransportException.usage(response));
    }
  }

  private static void requireCompleted(ChatResponse response) {
    requireNoRefusal(response);
    var metadata = response.getMetadata();
    var status = metadata.get(OpenAiResponsesMetadata.STATUS);
    if ("completed".equals(status)) return;
    // Spring AI maps this exact native reason to LENGTH. Existing Rei recovery owns
    // continuation; no partial reasoning/function item is admitted as a completed turn.
    if ("incomplete".equals(status)
        && "max_output_tokens".equals(metadata.get(OpenAiResponsesMetadata.INCOMPLETE_REASON))
        && OutputLimitDetector.isOutputLimitReached(response)) return;
    var category = ResponsesTransportException.Category.INVALID_RESPONSE;
    if ("incomplete".equals(status))
      category =
          "max_output_tokens".equals(metadata.get(OpenAiResponsesMetadata.INCOMPLETE_REASON))
              ? ResponsesTransportException.Category.OUTPUT_LIMIT
              : ResponsesTransportException.Category.INCOMPLETE;
    throw new ResponsesTransportException(
        category, ResponsesTransportException.usage(metadata.getUsage()));
  }

  private void validateComplete(ChatResponse response, Prompt request) {
    ResponsesReasoningReplay.validateComplete(response, request, endpoint);
    var itemIds = new java.util.HashSet<String>();
    var callIds = new java.util.HashSet<String>();
    try {
      for (var generation : response.getResults()) {
        for (var part : generation.getOutput().getParts()) {
          if (!(part instanceof org.springframework.ai.chat.messages.part.TextPart
                  || part instanceof org.springframework.ai.chat.messages.part.ReasoningPart
                  || part instanceof org.springframework.ai.chat.messages.part.ToolCallPart)
              || org.springframework.ai.chat.messages.part.StreamingParts.isPartial(part))
            throw new IllegalArgumentException();
          var status = part.attributes().get(OpenAiResponsesMetadata.ITEM_STATUS_ATTRIBUTE);
          if (status != null && !"completed".equals(status)) throw new IllegalArgumentException();
          if (part instanceof org.springframework.ai.chat.messages.part.TextPart) continue;
          var itemId = part.attributes().get(OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE);
          if (itemId == null || itemId.isBlank() || !itemIds.add(itemId))
            throw new IllegalArgumentException();
          if (part instanceof org.springframework.ai.chat.messages.part.ToolCallPart call) {
            var value = call.toolCall();
            if (value.id() == null
                || value.id().isBlank()
                || !callIds.add(value.id())
                || !"function".equals(value.type())
                || value.name() == null
                || value.name().isBlank()
                || value.arguments() == null
                || value.arguments().length() > 4_194_304) throw new IllegalArgumentException();
            var arguments = JSON.readTree(value.arguments());
            if (arguments == null || !arguments.isObject()) throw new IllegalArgumentException();
          }
        }
      }
    } catch (IllegalArgumentException | JsonProcessingException invalid) {
      throw new ResponsesTransportException(
          ResponsesTransportException.Category.INVALID_RESPONSE,
          ResponsesTransportException.usage(response));
    }
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    var request = prepare(prompt);
    return Flux.defer(
            () -> {
              var completed = new AtomicBoolean();
              var aggregated = new java.util.concurrent.atomic.AtomicReference<ChatResponse>();
              var chunks =
                  delegate.stream(request)
                      .doOnNext(response -> {
                        requireNoRefusal(response);
                        if (response.getMetadata().containsKey(OpenAiResponsesMetadata.STATUS)) {
                          requireCompleted(response);
                          completed.set(true);
                        }
                      })
                      .map(response -> ResponsesReasoningReplay.bind(response, request, endpoint));
              return new org.springframework.ai.chat.model.MessageAggregator()
                  .aggregate(chunks, aggregated::set)
                  .concatWith(
                      Flux.defer(() -> {
                        var result = aggregated.get();
                        if (!completed.get() || result == null)
                          return Flux.error(
                              new ResponsesTransportException(
                                  ResponsesTransportException.Category.INVALID_RESPONSE));
                        if (!OutputLimitDetector.isOutputLimitReached(result))
                          validateComplete(result, request);
                        return Flux.empty();
                      }));
            })
        .onErrorMap(ResponsesTransportException::normalize);
  }
}
