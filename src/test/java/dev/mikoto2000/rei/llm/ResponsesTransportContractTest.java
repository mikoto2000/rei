package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Exercises the pinned SDK's serialized HTTP request, without external traffic. */
class ResponsesTransportContractTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"408,false", "408,true", "503,false", "503,true"})
  void underlyingHttpLayerCannotReplayAResponsesRequest(int status, boolean streaming)
      throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var received = new java.util.concurrent.atomic.AtomicInteger();
    var wireBodies = new CopyOnWriteArrayList<JsonNode>();
    server.createContext(
        "/v1/responses",
        exchange -> {
          received.incrementAndGet();
          wireBodies.add(new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes()));
          var error =
              "{\"error\":{\"message\":\"fixture failure\",\"type\":\"fixture\"}}"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.getResponseHeaders().set("Retry-After", "0");
          exchange.sendResponseHeaders(status, error.length);
          try (var body = exchange.getResponseBody()) {
            body.write(error);
          }
          exchange.close();
        });
    server.start();
    try {
      passThrough = true;
      var options =
          options().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build();
      var model = transport(options);
      assertThatThrownBy(
              () -> {
                if (streaming) model.stream(new Prompt("fixture", options)).collectList().block();
                else model.call(new Prompt("fixture", options));
              })
          .isInstanceOf(ResponsesTransportException.class);
      assertThat(received.get()).isEqualTo(1);
      assertThat(wireBodies).containsExactly(requests.getFirst());
    } finally {
      server.stop(0);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"zero", "negative", "submillisecond", "extended"})
  void invalidRequestDeadlinesAreRejectedBeforeHttp(String condition) {
    var defaults = options().build();
    var timeout =
        switch (condition) {
          case "zero" -> java.time.Duration.ZERO;
          case "negative" -> java.time.Duration.ofMillis(-1);
          case "submillisecond" -> java.time.Duration.ofNanos(1);
          default -> java.time.Duration.ofMinutes(6);
        };
    var request = defaults.mutate().timeout(timeout).build();
    assertThatThrownBy(() -> transport(defaults).call(new Prompt("fixture", request)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void undeclaredRawExtensionsCannotSilentlyBypassTypedValidation() {
    var options =
        options()
            .extraBody(java.util.Map.of("unreviewed_extension", "private-reasoning fixture-secret"))
            .build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("extraBody")
        .hasMessageNotContaining("private-reasoning")
        .hasMessageNotContaining("fixture-secret");
    assertThat(requests).isEmpty();
  }

  @Test
  void deploymentAliasCannotReplaceTheResolvedWireModelAtConstruction() {
    var defaults = options().build();
    var changed = defaults.mutate().deploymentName("other-deployment").build();
    assertThatThrownBy(() -> ResponsesAdapter.create(endpoint(defaults), changed, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolved");
  }

  @Test
  void capabilityCollectionIsImmutableAndHasStableFingerprintOrder() {
    var mutable =
        java.util.EnumSet.of(LlmCapabilities.Setting.TEMPERATURE, LlmCapabilities.Setting.TOP_P);
    supportedSettings = mutable;
    var frozen = endpoint(options().build());
    var fingerprint = frozen.fingerprint();
    mutable.clear();
    assertThat(frozen.capabilities().supportedSettings())
        .containsExactly(LlmCapabilities.Setting.TEMPERATURE, LlmCapabilities.Setting.TOP_P);
    assertThat(frozen.fingerprint()).isEqualTo(fingerprint);
    assertThatThrownBy(() -> frozen.capabilities().supportedSettings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    supportedSettings =
        new java.util.LinkedHashSet<>(
            List.of(LlmCapabilities.Setting.TOP_P, LlmCapabilities.Setting.TEMPERATURE));
    assertThat(endpoint(options().build()).fingerprint()).isEqualTo(fingerprint);
  }

  @Test
  void generatedDefaultNativeOptionsReachFinalHttpJson() {
    var model = transport(options().model("feature-fixture").maxOutputTokens(123).build());
    model.call(new Prompt("fixture"));
    assertThat(requests.getFirst().path("model").asText()).isEqualTo("feature-fixture");
    assertThat(requests.getFirst().path("max_output_tokens").asInt()).isEqualTo(123);
  }

  @Test
  void structuredOutputRequiresDeclaredSupport() {
    supportedSettings = java.util.Set.of();
    var options = options().outputSchema("{\"type\":\"object\"}").build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("structuredOutput");
    assertThat(requests).isEmpty();
  }

  @Test
  void imageInputRequiresDeclaredSupport() {
    supportedSettings = java.util.Set.of();
    var options = options().build();
    var image =
        new org.springframework.ai.content.Media(
            org.springframework.util.MimeTypeUtils.IMAGE_PNG,
            new org.springframework.core.io.ByteArrayResource(new byte[] {1, 2, 3}));
    var user =
        org.springframework.ai.chat.messages.UserMessage.builder()
            .text("look")
            .media(image)
            .build();
    assertThatThrownBy(() -> transport(options).call(new Prompt(user, options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("imageInput");
    assertThat(requests).isEmpty();
  }

  @Test
  void namedPublishedToolChoiceUsesNativeFunctionShape() {
    var options =
        options()
            .toolCallbacks(callback("fixture_tool", "{\"type\":\"object\"}"))
            .toolChoice("{\"type\":\"function\",\"name\":\"fixture_tool\"}")
            .build();
    transport(options).call(new Prompt("fixture", options));
    assertThat(requests.getFirst().path("tool_choice").path("name").asText())
        .isEqualTo("fixture_tool");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void outputLimitUsesExistingLengthRecoveryAndKeepsNumericUsage(boolean streaming) {
    var incomplete =
        completed()
            .replaceFirst(
                "\"status\":\"completed\"",
                "\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}");
    contentType = streaming ? "text/event-stream" : "application/json";
    reply =
        streaming
            ? textDelta() + event("response.incomplete", "\"response\":" + incomplete)
            : incomplete;
    var options = options().build();
    var model = transport(options);
    org.springframework.ai.chat.model.ChatResponse result;
    if (streaming) {
      var completed =
          new java.util.concurrent.atomic.AtomicReference<
              org.springframework.ai.chat.model.ChatResponse>();
      new org.springframework.ai.chat.model.MessageAggregator()
          .aggregate(
              model.stream(new Prompt("fixture", options))
                  .doOnNext(chunk -> {
                    if (chunk.getMetadata().getUsage().getTotalTokens() > 0)
                      assertThat(ResponsesTransportException.usage(chunk.getMetadata().getUsage()))
                          .isEqualTo(
                              new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(
                                  3L, 2L, 5L, 1L, 1L));
                  }),
              completed::set)
          .blockLast();
      result = completed.get();
    } else result = model.call(new Prompt("fixture", options));
    assertThat(OutputLimitDetector.isOutputLimitReached(result)).isTrue();
    assertThat(result.getMetadata().getUsage().getTotalTokens()).isEqualTo(5);
    assertThat(ResponsesTransportException.usage(result))
        .isEqualTo(new dev.mikoto2000.rei.modelmessage.ModelTokenUsage(3L, 2L, 5L, 1L, 1L));
    assertThat(result.hasToolCalls()).isFalse();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void failedResponsesKeepOnlyNumericUsageAndSafeCategory(boolean streaming) {
    var failed =
        completed()
            .replaceFirst(
                "\"status\":\"completed\"",
                "\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"private-reasoning fixture-secret\"}");
    contentType = streaming ? "text/event-stream" : "application/json";
    reply = streaming ? event("response.failed", "\"response\":" + failed) : failed;
    var options = options().build();
    var model = transport(options);
    assertThatThrownBy(
            () -> {
              if (streaming) model.stream(new Prompt("fixture", options)).collectList().block();
              else model.call(new Prompt("fixture", options));
            })
        .isInstanceOfSatisfying(
            ResponsesTransportException.class,
            error -> {
              assertThat(error.category()).isEqualTo(ResponsesTransportException.Category.PROVIDER);
              assertThat(error)
                  .hasMessageNotContaining("private-reasoning")
                  .hasMessageNotContaining("fixture-secret");
              assertUsage(error);
            });
  }

  private void assertUsage(ResponsesTransportException error) {
    assertThat(error.usage()).isNotNull();
    assertThat(error.usage().input()).isEqualTo(3);
    assertThat(error.usage().output()).isEqualTo(2);
    assertThat(error.usage().total()).isEqualTo(5);
    assertThat(error.usage().cached()).isEqualTo(1);
    assertThat(error.usage().reasoning()).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"baseUrl", "model", "maxOutputTokens", "temperature"})
  void clientConstructionRequiresTheResolvedDescriptor(String field) {
    var options = options().build();
    var endpoint =
        new ResolvedLlmEndpoint(
            "fixture",
            1,
            LlmApiType.RESPONSES,
            field.equals("baseUrl") ? "https://other.invalid/v1" : options.getBaseUrl(),
            "fixture-credential",
            field.equals("model") ? "other-model" : options.getModel(),
            new LlmCapabilities(ReasoningReplayMode.NONE, 128),
            field.equals("maxOutputTokens") ? 32 : options.getMaxOutputTokens(),
            field.equals("temperature") ? Double.valueOf(0.2) : options.getTemperature(),
            readTimeout);
    assertThatThrownBy(() -> ResponsesAdapter.create(endpoint, options, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resolved");
  }

  @Test
  void optionsCannotBeGeneratedForADifferentBoundEndpoint() {
    var options = options().build();
    var other = endpoint(options.mutate().model("other-model").build());
    var model = (ResponsesAdapter) transport(options);
    assertThatThrownBy(() -> model.options(other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bound");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "{\"type\":\"web_search\"}",
        "{\"type\":\"function\",\"name\":\"unpublished\"}",
        "private-reasoning fixture-secret"
      })
  void invalidToolChoiceIsRejectedBeforeSdkParsingWithoutExposingItsValue(String choice) {
    var options =
        options()
            .toolCallbacks(callback("fixture_tool", "{\"type\":\"object\"}"))
            .toolChoice(choice)
            .build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool choice")
        .hasMessageNotContaining("private-reasoning")
        .hasMessageNotContaining("fixture-secret")
        .hasNoCause();
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "baseUrl",
        "apiKey",
        "organization",
        "headers",
        "outputLimit",
        "deployment",
        "timeout"
      })
  void runtimeOptionsCannotChangeBoundConnectionOrLimits(String field) {
    var defaults = options().build();
    var builder = defaults.mutate();
    switch (field) {
      case "baseUrl" -> builder.baseUrl("https://other.invalid/v1");
      case "apiKey" -> builder.apiKey("other-secret");
      case "organization" -> builder.organizationId("other-organization");
      case "headers" -> builder.customHeaders(java.util.Map.of("Authorization", "other-secret"));
      case "outputLimit" -> builder.maxOutputTokens(128);
      case "deployment" -> builder.deploymentName("other-deployment");
      case "timeout" -> builder.timeout(java.time.Duration.ofMinutes(6));
      default -> throw new AssertionError(field);
    }
    var runtime = builder.build();
    assertThatThrownBy(() -> transport(defaults).call(new Prompt("fixture", runtime)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("other-secret");
    assertThat(requests).isEmpty();
  }

  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final List<String> urls = new CopyOnWriteArrayList<>();
  private String reply = completed();
  private String contentType = "application/json";
  private volatile boolean bodyClosed;
  private int httpStatus = 200;
  private boolean passThrough;
  private java.time.Duration readTimeout = java.time.Duration.ofMinutes(5);
  private java.time.Duration sharedReadTimeout;
  private java.util.Set<LlmCapabilities.Setting> supportedSettings =
      java.util.Set.of(
          LlmCapabilities.Setting.STRUCTURED_OUTPUT, LlmCapabilities.Setting.IMAGE_INPUT);

  @Test
  void declaredSettingsAndNativeUsageDetailsSurviveSerialization() {
    supportedSettings = java.util.EnumSet.allOf(LlmCapabilities.Setting.class);
    var options =
        options()
            .temperature(0.2)
            .topP(0.8)
            .reasoningEffort("low")
            .reasoningSummary("auto")
            .parallelToolCalls(false)
            .build();
    var response = transport(options).call(new Prompt("fixture", options));
    var request = requests.getFirst();
    assertThat(request.path("temperature").asDouble()).isEqualTo(0.2);
    assertThat(request.path("top_p").asDouble()).isEqualTo(0.8);
    assertThat(request.path("reasoning").path("effort").asText()).isEqualTo("low");
    assertThat(request.path("reasoning").path("summary").asText()).isEqualTo("auto");
    assertThat(request.path("parallel_tool_calls").asBoolean(true)).isFalse();
    var usage = response.getMetadata().getUsage();
    assertThat(usage.getPromptTokens()).isEqualTo(3);
    assertThat(usage.getCompletionTokens()).isEqualTo(2);
    assertThat(usage.getTotalTokens()).isEqualTo(5);
    var nativeUsage = (com.openai.models.responses.ResponseUsage) usage.getNativeUsage();
    assertThat(nativeUsage.inputTokensDetails().cachedTokens()).isEqualTo(1);
    assertThat(nativeUsage.outputTokensDetails().reasoningTokens()).isEqualTo(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"temperature", "top_p", "verbosity"})
  void rawBodyCannotBypassCapabilityValidation(String field) {
    var options = options().extraBody(java.util.Map.of(field, "untrusted override")).build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field);
    assertThat(requests).isEmpty();
  }

  @Test
  void nativeRuntimeOptionsCannotRemoveTheResolvedOutputLimit() {
    var defaults = options().build();
    var runtime = defaults.mutate().maxOutputTokens(null).build();
    assertThatThrownBy(() -> transport(defaults).call(new Prompt("fixture", runtime)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("output limit");
    assertThat(requests).isEmpty();
  }

  @Test
  void validatesActualCallbackDefinitionAndSendsNonStrictFunctionSchema() {
    var callback = callback("fixture_tool", "{\"type\":\"object\",\"properties\":{}}");
    var options = options().toolCallbacks(callback).build();
    transport(options).call(new Prompt("fixture", options));
    var tool = requests.getFirst().path("tools").get(0);
    assertThat(tool.path("type").asText()).isEqualTo("function");
    assertThat(tool.path("name").asText()).isEqualTo("fixture_tool");
    assertThat(tool.path("strict").asBoolean(true)).isFalse();
    assertThat(tool.path("parameters").path("type").asText()).isEqualTo("object");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"duplicate", "name", "schema", "limit", "duplicateSchemaKey"})
  void invalidActualToolDefinitionsFailBeforeHttp(String invalid) {
    var normal = callback("fixture_tool", "{\"type\":\"object\",\"properties\":{}}");
    var callbacks =
        switch (invalid) {
          case "duplicate" -> List.of(normal, normal);
          case "duplicateSchemaKey" ->
              List.of(callback("fixture_tool", "{\"type\":\"array\",\"type\":\"object\"}"));
          case "name" -> List.of(callback("invalid name", "{\"type\":\"object\"}"));
          case "schema" -> List.of(callback("fixture_tool", "[]"));
          case "limit" ->
              java.util.stream.IntStream.range(0, 129)
                  .mapToObj(i -> callback("tool_" + i, "{\"type\":\"object\"}"))
                  .toList();
          default -> throw new AssertionError(invalid);
        };
    var options = options().toolCallbacks(callbacks).build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool");
    assertThat(requests).isEmpty();
  }

  private org.springframework.ai.tool.ToolCallback callback(String name, String schema) {
    return new org.springframework.ai.tool.ToolCallback() {
      @Override
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name(name)
            .description("fixture")
            .inputSchema(schema)
            .build();
      }

      @Override
      public String call(String input) {
        throw new AssertionError("Transport must never execute tools");
      }
    };
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void nativeClientsClassifySilentLoopbackConnectionsAsTimeout(boolean streaming) throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    server.createContext(
        "/v1/responses",
        exchange -> {
          try {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      passThrough = true;
      readTimeout = java.time.Duration.ofMillis(150);
      sharedReadTimeout = java.time.Duration.ofSeconds(5);
      var options =
          options().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build();
      var model = transport(options);
      long started = System.nanoTime();
      assertThatThrownBy(
              () -> {
                if (streaming) model.stream(new Prompt("fixture", options)).collectList().block();
                else model.call(new Prompt("fixture", options));
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TIMEOUT");
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
          .isLessThan(java.time.Duration.ofSeconds(3));
      assertThat(requests).hasSize(1);
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aRequestCanShortenButNeverExtendTheBoundTransportDeadline(boolean streaming)
      throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    server.createContext(
        "/v1/responses",
        exchange -> {
          try {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      passThrough = true;
      readTimeout = java.time.Duration.ofSeconds(5);
      sharedReadTimeout = java.time.Duration.ofSeconds(5);
      var options =
          options().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").build();
      var model = transport(options);
      var request = options.mutate().timeout(java.time.Duration.ofMillis(150)).build();
      long started = System.nanoTime();
      assertThatThrownBy(
              () -> {
                if (streaming) model.stream(new Prompt("fixture", request)).collectList().block();
                else model.call(new Prompt("fixture", request));
              })
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("TIMEOUT");
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
          .isLessThan(java.time.Duration.ofSeconds(3));
      assertThat(requests).hasSize(1);
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "401,AUTHENTICATION",
    "403,AUTHENTICATION",
    "429,RATE_LIMIT",
    "500,TRANSIENT",
    "400,PROVIDER"
  })
  void httpFailuresAreClassifiedWithoutLeakingProviderBodyOrRetrying(int status, String category) {
    httpStatus = status;
    reply =
        "{\"error\":{\"message\":\"private-reasoning fixture-secret\",\"type\":\"fixture\",\"code\":\"fixture\"}}";
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(category)
        .hasMessageNotContaining("private-reasoning")
        .hasMessageNotContaining("fixture-secret")
        .hasNoCause();
    assertThat(requests).hasSize(1);
  }

  @Test
  void sseErrorCannotExposeProviderMessage() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                org.springframework.ai.chat.model.MessageAggregator.class);
    var captured =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
      contentType = "text/event-stream";
      reply =
          textDelta()
              + event(
                  "error",
                  "\"code\":\"fixture\",\"message\":\"private-reasoning fixture-secret\",\"param\":null");
      var options = options().build();
      assertThatThrownBy(
              () -> transport(options).stream(new Prompt("fixture", options)).collectList().block())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("PROVIDER")
          .hasMessageNotContaining("private-reasoning")
          .hasMessageNotContaining("fixture-secret");
      for (var logged : captured.list) {
        assertThat(logged.getFormattedMessage())
            .doesNotContain("private-reasoning", "fixture-secret");
        if (logged.getThrowableProxy() != null)
          assertThat(
                  ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                      logged.getThrowableProxy()))
              .doesNotContain("private-reasoning", "fixture-secret");
      }
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "response.output_text.delta", "response.reasoning_text.delta",
        "response.reasoning_summary_text.delta", "response.function_call_arguments.delta",
        "response.refusal.delta"
      })
  void malformedSdkDeltasNeverReachTheInnerAggregatorLogger(String type) {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var captured =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
      contentType = "text/event-stream";
      reply =
          type.equals("response.function_call_arguments.delta")
              ? event(
                  "response.output_item.added",
                  "\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_fixture\",\"call_id\":\"call_fixture\",\"name\":\"fixture\",\"arguments\":\"\",\"status\":\"in_progress\"}")
              : "";
      reply +=
          event(
                  type,
                  "\"item_id\":\"fixture_item\",\"output_index\":0,\"content_index\":0,\"summary_index\":0,\"delta\":{\"PRIVATE-delta-canary\":\"PRIVATE-secret\"}")
              + event("response.completed", "\"response\":" + completed());
      var options = options().build();
      assertThatThrownBy(
              () -> transport(options).stream(new Prompt("fixture", options)).collectList().block())
          .isInstanceOf(ResponsesTransportException.class)
          .hasMessageContaining("INVALID_RESPONSE")
          .hasMessageNotContaining("PRIVATE")
          .hasNoCause();
      for (var logged : captured.list) {
        assertThat(logged.getFormattedMessage()).doesNotContain("PRIVATE-");
        if (logged.getThrowableProxy() != null)
          assertThat(
                  ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                      logged.getThrowableProxy()))
              .doesNotContain("PRIVATE-");
      }
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "true,text_done", "true,reasoning_done", "true,function_added", "true,index_done",
    "true,text_terminal", "true,refusal_terminal", "false,text_terminal", "false,refusal_terminal"
  })
  void malformedNestedItemsCannotReachSdkOrAggregatorLogs(boolean streaming, String defect)
      throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var captured =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
      var mapper = new ObjectMapper();
      var response = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(completed());
      var item = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("output").get(0);
      var secret = mapper.createObjectNode().put("PRIVATE-nested-canary", "PRIVATE-value");
      if (defect.equals("reasoning_done")) {
        item =
            (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(
                    "{\"type\":\"reasoning\",\"id\":\"rs_fixture\",\"summary\":[],\"status\":\"completed\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"temporary\"}]}");
        ((com.fasterxml.jackson.databind.node.ObjectNode) item.path("content").get(0))
            .set("text", secret);
      } else if (defect.equals("function_added")) {
        item =
            (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(
                    "{\"type\":\"function_call\",\"id\":\"fc_fixture\",\"call_id\":\"call_fixture\",\"name\":\"temporary\",\"arguments\":\"\",\"status\":\"in_progress\"}");
        item.set("name", secret);
      } else if (!defect.equals("index_done")) {
        var content = (com.fasterxml.jackson.databind.node.ObjectNode) item.path("content").get(0);
        if (defect.equals("refusal_terminal")) {
          content.put("type", "refusal");
          content.remove("text");
          content.set("refusal", secret);
        } else content.set("text", secret);
      }
      contentType = streaming ? "text/event-stream" : "application/json";
      if (!streaming) reply = response.toString();
      else if (defect.endsWith("terminal"))
        reply = event("response.completed", "\"response\":" + response);
      else
        reply =
            event(
                    defect.equals("function_added")
                        ? "response.output_item.added"
                        : "response.output_item.done",
                    "\"output_index\":"
                        + (defect.equals("index_done") ? secret.toString() : "0")
                        + ",\"item\":"
                        + item)
                + event("response.completed", "\"response\":" + completed());
      var options = options().build();
      assertThatThrownBy(
              () -> {
                if (streaming)
                  transport(options).stream(new Prompt("fixture", options)).collectList().block();
                else transport(options).call(new Prompt("fixture", options));
              })
          .isInstanceOf(ResponsesTransportException.class)
          .hasMessageContaining("INVALID_RESPONSE")
          .hasMessageNotContaining("PRIVATE-")
          .hasNoCause();
      for (var logged : captured.list) {
        assertThat(logged.getFormattedMessage()).doesNotContain("PRIVATE-");
        if (logged.getThrowableProxy() != null)
          assertThat(
                  ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
                      logged.getThrowableProxy()))
              .doesNotContain("PRIVATE-");
      }
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  @Test
  void cancellationClosesNativeSseBodyAndDoesNotRequireCompletion() {
    contentType = "text/event-stream";
    reply = textDelta();
    var options = options().build();
    var first = transport(options).stream(new Prompt("fixture", options)).take(1).blockFirst();
    assertThat(first.getResult().getOutput().getText()).isEqualTo("done");
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(bodyClosed).isTrue());
  }

  @Test
  void nativeOptionsCannotSwitchTheBoundModel() {
    var defaults = options().build();
    var other = defaults.mutate().model("other-model").build();
    assertThatThrownBy(() -> transport(defaults).call(new Prompt("fixture", other)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model");
    assertThat(requests).isEmpty();
  }

  @Test
  void nativeOptionsCannotEnableHiddenSdkRetries() {
    var defaults = options().build();
    var other = defaults.mutate().maxRetries(2).build();
    assertThatThrownBy(() -> transport(defaults).call(new Prompt("fixture", other)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retries");
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "temperature",
        "topP",
        "reasoningEffort",
        "reasoningSummary",
        "parallelToolCalls",
        "verbosity"
      })
  void unspecifiedCapabilitiesCannotSilentlyAcceptExplicitSettings(String setting) {
    var builder = options();
    switch (setting) {
      case "temperature" -> builder.temperature(0.2);
      case "topP" -> builder.topP(0.8);
      case "reasoningEffort" -> builder.reasoningEffort("low");
      case "reasoningSummary" -> builder.reasoningSummary("auto");
      case "parallelToolCalls" -> builder.parallelToolCalls(false);
      case "verbosity" -> builder.verbosity("low");
      default -> throw new AssertionError(setting);
    }
    var options = builder.build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(setting);
    assertThat(requests).isEmpty();
  }

  @Test
  void structuredOutputUsesNativeTextFormat() {
    var options = options().outputSchema("{\"type\":\"object\",\"properties\":{}}").build();
    transport(options).call(new Prompt("fixture", options));
    var format = requests.getFirst().path("text").path("format");
    assertThat(format.path("type").asText()).isEqualTo("json_schema");
    assertThat(format.path("schema").path("type").asText()).isEqualTo("object");
    assertThat(requests.getFirst().has("response_format")).isFalse();
  }

  @Test
  void imageMediaUsesNativeResponsesInputImage() {
    var options = options().build();
    var image =
        new org.springframework.ai.content.Media(
            org.springframework.util.MimeTypeUtils.IMAGE_PNG,
            new org.springframework.core.io.ByteArrayResource(new byte[] {1, 2, 3}));
    var user =
        org.springframework.ai.chat.messages.UserMessage.builder()
            .text("look")
            .media(image)
            .build();
    transport(options).call(new Prompt(user, options));
    var content = requests.getFirst().path("input").get(0).path("content");
    assertThat(content.get(1).path("type").asText()).isEqualTo("input_image");
    assertThat(content.get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,AQID");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"hostedTools", "truncation", "maxToolCalls", "strict"})
  void rejectsSettingsOutsideReisInitialContract(String setting) {
    var builder = options();
    switch (setting) {
      case "hostedTools" ->
          builder.hostedTools(
              List.of(org.springframework.ai.openai.responses.HostedTool.WebSearch.of()));
      case "truncation" -> builder.truncation("auto");
      case "maxToolCalls" -> builder.maxToolCalls(1);
      case "strict" -> builder.strict(true);
      default -> throw new AssertionError(setting);
    }
    var options = builder.build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(setting);
    assertThat(requests).isEmpty();
  }

  @Test
  void streamingTextAndUsageComeThroughTheNativeSseParser() {
    contentType = "text/event-stream";
    reply = textDelta() + event("response.completed", "\"response\":" + completed());
    var options = options().build();
    var chunks = transport(options).stream(new Prompt("fixture", options)).collectList().block();
    assertThat(chunks).hasSize(2);
    assertThat(chunks.getFirst().getResult().getOutput().getText()).isEqualTo("done");
    assertThat(chunks.getLast().getMetadata().getUsage().getTotalTokens()).isEqualTo(5);
    assertThat(requests.getFirst().path("stream").asBoolean()).isTrue();
  }

  @Test
  void streamEndingWithoutTerminalResponseFails() {
    contentType = "text/event-stream";
    reply = textDelta();
    var options = options().build();
    assertThatThrownBy(
            () -> transport(options).stream(new Prompt("fixture", options)).collectList().block())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
  }

  @Test
  void incompleteStreamFailsInsteadOfCompletingNormally() {
    contentType = "text/event-stream";
    reply =
        textDelta()
            + event(
                "response.incomplete",
                "\"response\":"
                    + completed()
                        .replaceFirst("\"status\":\"completed\"", "\"status\":\"incomplete\""));
    var options = options().build();
    assertThatThrownBy(
            () -> transport(options).stream(new Prompt("fixture", options)).collectList().block())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
  }

  private String textDelta() {
    return event(
        "response.output_text.delta",
        "\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"done\",\"logprobs\":[]");
  }

  private String event(String type, String fields) {
    return "event: "
        + type
        + "\ndata: {\"type\":\""
        + type
        + "\",\"sequence_number\":1,"
        + fields.replace("\n", "")
        + "}\n\n";
  }

  @Test
  void toolFreeRequestsOmitBothToolFieldsAndUseOnlyResponsesTokenLimits() {
    var options = options().toolCallbacks(List.of()).toolChoice("none").build();
    var response = transport(options).call(new Prompt("fixture input", options));
    assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(urls).containsExactly("https://responses-fixture.invalid/v1/responses");
    assertThat(requests).hasSize(1);
    var request = requests.getFirst();
    assertThat(request.path("model").asText()).isEqualTo("fixture-model");
    assertThat(request.path("max_output_tokens").asInt()).isEqualTo(64);
    assertThat(request.path("store").asBoolean(true)).isFalse();
    assertThat(options.getToolChoice()).isEqualTo("none");
    assertThat(request.toString()).doesNotContain("fixture-secret");
    for (String field :
        List.of(
            "tools",
            "tool_choice",
            "max_tokens",
            "max_completion_tokens",
            "previous_response_id",
            "conversation")) assertThat(request.has(field)).as(field).isFalse();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
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
        "truncation"
      })
  void extraBodyCannotOverrideProtectedProtocolFields(String field) {
    var options = options().extraBody(java.util.Map.of(field, "untrusted override")).build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field)
        .hasMessageNotContaining("untrusted override")
        .hasMessageNotContaining("fixture-secret");
    assertThat(requests).isEmpty();
  }

  @Test
  void rejectsChatCompletionsOptionsBeforeSending() {
    var legacy =
        org.springframework.ai.openai.OpenAiChatOptions.builder()
            .model("fixture-model")
            .maxTokens(64)
            .build();
    assertThatThrownBy(() -> transport(options().build()).call(new Prompt("fixture", legacy)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Responses native");
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"incomplete", "in_progress", "queued", "cancelled", "unknown"})
  void onlyExplicitlyCompletedResponsesAreSuccessful(String status) {
    reply = completed().replaceFirst("\"status\":\"completed\"", "\"status\":\"" + status + "\"");
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
  }

  @Test
  void missingResponseStatusDoesNotBecomeCompletedThroughGenerationDefaults() {
    reply = completed().replaceFirst("\"status\":\"completed\",", "");
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
  }

  private OpenAiResponsesChatOptions.Builder options() {
    return OpenAiResponsesChatOptions.builder()
        .baseUrl("https://responses-fixture.invalid/v1")
        .apiKey("fixture-secret")
        .model("fixture-model")
        .maxOutputTokens(64)
        .maxRetries(0);
  }

  private ChatModel transport(OpenAiResponsesChatOptions options) {
    org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer customizer =
        builder ->
            builder.interceptor(chain -> {
              var buffer = new okio.Buffer();
              chain.request().body().writeTo(buffer);
              requests.add(new ObjectMapper().readTree(buffer.readByteArray()));
              urls.add(chain.request().url().toString());
              if (passThrough) return chain.proceed(chain.request());
              return new okhttp3.Response.Builder()
                  .request(chain.request())
                  .protocol(okhttp3.Protocol.HTTP_1_1)
                  .code(httpStatus)
                  .message("fixture")
                  .body(responseBody())
                  .build();
            });
    var customizers =
        new java.util.ArrayList<
            org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer>();
    customizers.add(customizer);
    if (sharedReadTimeout != null)
      customizers.add(
          builder -> builder.interceptor(new ChatStreamTimeoutInterceptor(sharedReadTimeout)));
    return ResponsesAdapter.create(endpoint(options), options, customizers);
  }

  private ResolvedLlmEndpoint endpoint(OpenAiResponsesChatOptions options) {
    return new ResolvedLlmEndpoint(
        "fixture",
        1,
        LlmApiType.RESPONSES,
        options.getBaseUrl(),
        "fixture-credential",
        options.getModel(),
        new LlmCapabilities(ReasoningReplayMode.NONE, 128, supportedSettings),
        options.getMaxOutputTokens(),
        options.getTemperature(),
        readTimeout);
  }

  private okhttp3.ResponseBody responseBody() {
    var body = okhttp3.ResponseBody.create(reply, okhttp3.MediaType.get(contentType));
    var source =
        okio.Okio.buffer(
            new okio.ForwardingSource(body.source()) {
              @Override
              public void close() throws java.io.IOException {
                bodyClosed = true;
                super.close();
              }
            });
    return new okhttp3.ResponseBody() {
      @Override
      public okhttp3.MediaType contentType() {
        return body.contentType();
      }

      @Override
      public long contentLength() {
        return body.contentLength();
      }

      @Override
      public okio.BufferedSource source() {
        return source;
      }
    };
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"JSON_OBJECT", "JSON_SCHEMA"})
  void sharedFeatureResponseFormatUsesNativeTextFormat(String type) {
    var format = new org.springframework.ai.openai.OpenAiChatModel.ResponseFormat();
    format.setType(org.springframework.ai.openai.OpenAiChatModel.ResponseFormat.Type.valueOf(type));
    if (type.equals("JSON_SCHEMA")) {
      format.setJsonSchema(
          "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false}");
      format.setStrict(true);
    }
    var options = options().responseFormat(format).build();
    reply = completed().replace("\"text\":\"done\"", "\"text\":\"{}\"");
    transport(options).call(new Prompt("fixture JSON", options));
    var sent = requests.getFirst().path("text").path("format");
    assertThat(sent.path("type").asText()).isEqualTo(type.toLowerCase(java.util.Locale.ROOT));
    assertThat(requests.getFirst().has("response_format")).isFalse();
    if (type.equals("JSON_SCHEMA")) {
      assertThat(sent.path("strict").asBoolean()).isTrue();
      assertThat(sent.path("schema").path("properties").has("answer")).isTrue();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void refusalsAreReportedBeforeAnEmptySuccessfulAnswerCanBePersisted(boolean streaming) {
    var refused =
        completed()
            .replace(
                "{\"type\":\"output_text\",\"text\":\"done\",\"annotations\":[]}",
                "{\"type\":\"refusal\",\"refusal\":\"PRIVATE refusal fixture\"}");
    assertThat(refused).contains("PRIVATE refusal fixture").doesNotContain("output_text");
    contentType = streaming ? "text/event-stream" : "application/json";
    reply = streaming ? event("response.completed", "\"response\":" + refused) : refused;
    var options = options().build();
    var model = transport(options);
    assertThatThrownBy(
            () -> {
              if (streaming) model.stream(new Prompt("fixture", options)).collectList().block();
              else model.call(new Prompt("fixture", options));
            })
        .isInstanceOfSatisfying(
            ResponsesTransportException.class,
            error -> {
              assertThat(error.category().name()).isEqualTo("REFUSAL");
              assertThat(error).hasMessageNotContaining("PRIVATE");
              assertUsage(error);
            });
    assertThat(requests).hasSize(1);
  }

  @Test
  void streamingRefusalDeltaStopsBeforeOrdinaryObserversReceiveIt() {
    contentType = "text/event-stream";
    reply =
        event(
                "response.refusal.delta",
                "\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"PRIVATE refusal delta\"")
            + event("response.completed", "\"response\":" + completed());
    var options = options().build();
    var model = transport(options);
    var observed = new java.util.concurrent.atomic.AtomicInteger();
    assertThatThrownBy(() ->
            model.stream(new Prompt("fixture", options))
                .doOnNext(chunk -> observed.incrementAndGet())
                .collectList()
                .block())
        .isInstanceOfSatisfying(
            ResponsesTransportException.class,
            error -> {
              assertThat(error.category().name()).isEqualTo("REFUSAL");
              assertThat(error).hasMessageNotContaining("PRIVATE");
              assertThat(error.usage()).isNull();
            });
    assertThat(observed.get()).isZero();
    assertThat(requests).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(ResponsesTransportException.Category.class)
  void safeTransportCategoriesReachTheExistingUserDiagnostic(
      ResponsesTransportException.Category category) {
    var expected =
        switch (category.name()) {
          case "AUTHENTICATION" -> "authentication_failed";
          case "RATE_LIMIT" -> "rate_limited";
          case "TRANSIENT" -> "provider_unavailable";
          case "PROVIDER" -> "provider_failure";
          case "TIMEOUT" -> "timeout";
          case "OUTPUT_LIMIT" -> "output_limit";
          case "INCOMPLETE" -> "response_incomplete";
          case "REFUSAL" -> "model_refusal";
          case "INVALID_RESPONSE" -> "invalid_response";
          default -> throw new AssertionError(category);
        };
    var failure = new ResponsesTransportException(category);
    var diagnostic =
        dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.classify(
            new RuntimeException("PRIVATE provider wrapper", failure));
    assertThat(diagnostic.code()).isEqualTo(expected);
    assertThat(diagnostic.message()).doesNotContain("PRIVATE");
    assertThat(failure.getCause()).isNull();
  }

  private String completed() {
    return """
    {"id":"resp_fixture","object":"response","created_at":0,"status":"completed",
     "model":"fixture-model","output":[{"type":"message","id":"msg_fixture",
     "role":"assistant","status":"completed","phase":"final_answer",
     "content":[{"type":"output_text","text":"done","annotations":[]}]}],
     "usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5,
      "input_tokens_details":{"cached_tokens":1},
      "output_tokens_details":{"reasoning_tokens":1}}}
    """;
  }
}
