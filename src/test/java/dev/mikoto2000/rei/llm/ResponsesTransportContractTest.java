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
import org.springframework.ai.openai.responses.OpenAiResponsesChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Exercises the pinned SDK's serialized HTTP request, without external traffic. */
class ResponsesTransportContractTest {
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final List<String> urls = new CopyOnWriteArrayList<>();
  private String reply = completed();
  private String contentType = "application/json";
  private volatile boolean bodyClosed;
  private int httpStatus = 200;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "401,AUTHENTICATION", "403,AUTHENTICATION", "429,RATE_LIMIT", "500,TRANSIENT", "400,PROVIDER"
  })
  void httpFailuresAreClassifiedWithoutLeakingProviderBodyOrRetrying(int status, String category) {
    httpStatus = status;
    reply = "{\"error\":{\"message\":\"private-reasoning fixture-secret\",\"type\":\"fixture\",\"code\":\"fixture\"}}";
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining(category)
        .hasMessageNotContaining("private-reasoning").hasMessageNotContaining("fixture-secret")
        .hasNoCause();
    assertThat(requests).hasSize(1);
  }

  @Test
  void sseErrorCannotExposeProviderMessage() {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
        org.springframework.ai.chat.model.MessageAggregator.class);
    var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
    contentType = "text/event-stream";
    reply = textDelta() + event("error", "\"code\":\"fixture\",\"message\":\"private-reasoning fixture-secret\",\"param\":null");
    var options = options().build();
    assertThatThrownBy(() -> transport(options).stream(new Prompt("fixture", options)).collectList().block())
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROVIDER")
        .hasMessageNotContaining("private-reasoning").hasMessageNotContaining("fixture-secret");
      for (var logged : captured.list) {
        assertThat(logged.getFormattedMessage()).doesNotContain("private-reasoning", "fixture-secret");
        if (logged.getThrowableProxy() != null)
          assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(logged.getThrowableProxy()))
              .doesNotContain("private-reasoning", "fixture-secret");
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
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(bodyClosed).isTrue());
  }

  @Test
  void nativeOptionsCannotSwitchTheBoundModel() {
    var defaults = options().build();
    var other = defaults.mutate().model("other-model").build();
    assertThatThrownBy(() -> transport(defaults).call(new Prompt("fixture", other)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
    assertThat(requests).isEmpty();
  }

  @Test
  void nativeOptionsCannotEnableHiddenSdkRetries() {
    var defaults = options().build();
    var other = defaults.mutate().maxRetries(2).build();
    assertThatThrownBy(() -> transport(defaults).call(new Prompt("fixture", other)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retries");
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
    "temperature", "topP", "reasoningEffort", "reasoningSummary", "parallelToolCalls"
  })
  void unspecifiedCapabilitiesCannotSilentlyAcceptExplicitSettings(String setting) {
    var builder = options();
    switch (setting) {
      case "temperature" -> builder.temperature(0.2);
      case "topP" -> builder.topP(0.8);
      case "reasoningEffort" -> builder.reasoningEffort("low");
      case "reasoningSummary" -> builder.reasoningSummary("auto");
      case "parallelToolCalls" -> builder.parallelToolCalls(false);
      default -> throw new AssertionError(setting);
    }
    var options = builder.build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(setting);
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
    var image = new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,
        new org.springframework.core.io.ByteArrayResource(new byte[] {1, 2, 3}));
    var user = org.springframework.ai.chat.messages.UserMessage.builder().text("look").media(image).build();
    transport(options).call(new Prompt(user, options));
    var content = requests.getFirst().path("input").get(0).path("content");
    assertThat(content.get(1).path("type").asText()).isEqualTo("input_image");
    assertThat(content.get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,AQID");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
    "hostedTools", "truncation", "maxToolCalls", "strict"
  })
  void rejectsSettingsOutsideReisInitialContract(String setting) {
    var builder = options();
    switch (setting) {
      case "hostedTools" -> builder.hostedTools(List.of(
          org.springframework.ai.openai.responses.HostedTool.WebSearch.of()));
      case "truncation" -> builder.truncation("auto");
      case "maxToolCalls" -> builder.maxToolCalls(1);
      case "strict" -> builder.strict(true);
      default -> throw new AssertionError(setting);
    }
    var options = builder.build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(setting);
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
    assertThatThrownBy(() -> transport(options).stream(new Prompt("fixture", options))
        .collectList().block()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
  }

  @Test
  void incompleteStreamFailsInsteadOfCompletingNormally() {
    contentType = "text/event-stream";
    reply = textDelta() + event("response.incomplete", "\"response\":"
        + completed().replaceFirst("\"status\":\"completed\"", "\"status\":\"incomplete\""));
    var options = options().build();
    assertThatThrownBy(() -> transport(options).stream(new Prompt("fixture", options))
        .collectList().block()).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Responses");
  }

  private String textDelta() {
    return event("response.output_text.delta",
        "\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"done\",\"logprobs\":[]");
  }

  private String event(String type, String fields) {
    return "event: " + type + "\ndata: {\"type\":\"" + type
        + "\",\"sequence_number\":1," + fields.replace("\n", "") + "}\n\n";
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
    for (String field : List.of("tools", "tool_choice", "max_tokens", "max_completion_tokens",
        "previous_response_id", "conversation"))
      assertThat(request.has(field)).as(field).isFalse();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
    "model", "input", "instructions", "tools", "tool_choice", "store", "previous_response_id",
    "conversation", "max_tokens", "max_completion_tokens", "max_output_tokens", "reasoning",
    "text", "stream", "include", "parallel_tool_calls", "response_format", "messages",
    "functions", "function_call", "chat_template_kwargs", "background", "context_management",
    "truncation"
  })
  void extraBodyCannotOverrideProtectedProtocolFields(String field) {
    var options = options().extraBody(java.util.Map.of(field, "untrusted override")).build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field)
        .hasMessageNotContaining("untrusted override").hasMessageNotContaining("fixture-secret");
    assertThat(requests).isEmpty();
  }

  @Test
  void rejectsChatCompletionsOptionsBeforeSending() {
    var legacy = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .model("fixture-model").maxTokens(64).build();
    assertThatThrownBy(() -> transport(options().build()).call(new Prompt("fixture", legacy)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Responses native");
    assertThat(requests).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
    "incomplete", "in_progress", "queued", "cancelled", "unknown"
  })
  void onlyExplicitlyCompletedResponsesAreSuccessful(String status) {
    reply = completed().replaceFirst("\"status\":\"completed\"",
        "\"status\":\"" + status + "\"");
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Responses");
  }

  @Test
  void missingResponseStatusDoesNotBecomeCompletedThroughGenerationDefaults() {
    reply = completed().replaceFirst("\"status\":\"completed\",", "");
    var options = options().build();
    assertThatThrownBy(() -> transport(options).call(new Prompt("fixture", options)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Responses");
  }

  private OpenAiResponsesChatOptions.Builder options() {
    return OpenAiResponsesChatOptions.builder()
        .baseUrl("https://responses-fixture.invalid/v1")
        .apiKey("fixture-secret").model("fixture-model").maxOutputTokens(64).maxRetries(0);
  }

  private ChatModel transport(OpenAiResponsesChatOptions options) {
    org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer customizer =
        builder -> builder.interceptor(chain -> {
          var buffer = new okio.Buffer();
          chain.request().body().writeTo(buffer);
          requests.add(new ObjectMapper().readTree(buffer.readByteArray()));
          urls.add(chain.request().url().toString());
          return new okhttp3.Response.Builder().request(chain.request())
              .protocol(okhttp3.Protocol.HTTP_1_1).code(httpStatus).message("fixture")
              .body(responseBody()).build();
        });
    var endpoint = new ResolvedLlmEndpoint("fixture", 1, LlmApiType.RESPONSES,
        options.getBaseUrl(), "fixture-credential", options.getModel(),
        new LlmCapabilities(ReasoningReplayMode.NONE, 128, java.util.Set.of(
            LlmCapabilities.Setting.STRUCTURED_OUTPUT, LlmCapabilities.Setting.IMAGE_INPUT)), options.getMaxOutputTokens(),
        options.getTemperature(), java.time.Duration.ofMinutes(5));
    return ResponsesAdapter.create(endpoint, options, List.of(customizer));
  }

  private okhttp3.ResponseBody responseBody() {
    var body = okhttp3.ResponseBody.create(reply, okhttp3.MediaType.get(contentType));
    var source = okio.Okio.buffer(new okio.ForwardingSource(body.source()) {
      @Override
      public void close() throws java.io.IOException {
        bodyClosed = true;
        super.close();
      }
    });
    return new okhttp3.ResponseBody() {
      @Override public okhttp3.MediaType contentType() { return body.contentType(); }
      @Override public long contentLength() { return body.contentLength(); }
      @Override public okio.BufferedSource source() { return source; }
    };
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
