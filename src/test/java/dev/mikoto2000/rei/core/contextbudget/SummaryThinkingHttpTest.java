package dev.mikoto2000.rei.core.contextbudget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

/** Verify summary-only template overrides on the actual SDK/SSE path. */
@Tag("integration")
class SummaryThinkingHttpTest {
  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {false, true})
  void sendsOnlyExplicitSummaryOverrideAndReturnsAnswerWithoutReasoning(Boolean thinking)
      throws Exception {
    var request = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            String chunks =
                """
                data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"analysis that must not become summary text"},"finish_reason":null}]}

                data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"content":"Decisions: migration pending; verification not performed."},"finish_reason":null}]}

                data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
            body.write(chunks.getBytes(StandardCharsets.UTF_8));
          }
        });
    server.start();
    try {
      var template = new LinkedHashMap<String, Object>();
      template.put("format", "original");
      var extra = new LinkedHashMap<String, Object>();
      extra.put("chat_template_kwargs", template);
      extra.put("provider_option", "preserved");
      var base =
          OpenAiChatOptions.builder()
              .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
              .apiKey("test")
              .model("test")
              .maxRetries(0)
              .maxTokens(16384)
              .extraBody(extra)
              .build();
      var provider = mock(LlmModelProvider.class);
      when(provider.chatModel("chat")).thenReturn(OpenAiChatModel.builder().options(base).build());
      when(provider.chatOptions("chat", null)).thenReturn(base);
      var properties = new ContextCompressionProperties();
      properties.setSummaryEnableThinking(thinking);
      var summary =
          compressor(provider, properties)
              .summarizeResult(
                  "",
                  List.of(new UserMessage("Synthetic history: migration remains pending.")),
                  3000,
                  new Prompt("owner"));
      assertEquals("Decisions: migration pending; verification not performed.", summary.text());
      var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(request.get());
      var sentTemplate = json.get("chat_template_kwargs");
      assertEquals("original", sentTemplate.get("format").asString());
      assertEquals("preserved", json.get("provider_option").asString());
      if (thinking == null) assertFalse(sentTemplate.has("enable_thinking"));
      else assertEquals(thinking, sentTemplate.get("enable_thinking").asBoolean());
      assertEquals(Boolean.FALSE.equals(thinking) ? 3000 : 11192, json.get("max_tokens").asInt());
      assertEquals("none", json.get("tool_choice").asString());
      assertFalse(json.has("tools"));
      assertEquals(16384, base.getMaxTokens());
      assertEquals(Map.of("format", "original"), template);
      assertEquals(
          Map.of(
              "chat_template_kwargs", Map.of("format", "original"), "provider_option", "preserved"),
          base.getExtraBody());
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {false, true})
  void overridesExistingThinkingOnlyOnTheSummaryRequest(Boolean thinking) {
    var model = mock(ChatModel.class);
    var template = new LinkedHashMap<String, Object>();
    template.put("enable_thinking", true);
    template.put("format", "original");
    var base =
        OpenAiChatOptions.builder().extraBody(Map.of("chat_template_kwargs", template)).build();
    var provider = mock(LlmModelProvider.class);
    when(provider.chatModel("chat")).thenReturn(model);
    when(provider.chatOptions("chat", null)).thenReturn(base);
    when(model.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          var options = (OpenAiChatOptions) ((Prompt) invocation.getArgument(0)).getOptions();
          assertEquals(
              Map.of("enable_thinking", thinking == null ? true : thinking, "format", "original"),
              options.getExtraBody().get("chat_template_kwargs"));
          assertEquals(List.of(), options.getToolCallbacks());
          assertEquals("none", options.getToolChoice());
          return Flux.just(
              new ChatResponse(List.of(new Generation(new AssistantMessage("summary")))));
        });
    var properties = new ContextCompressionProperties();
    properties.setSummaryEnableThinking(thinking);
    assertEquals(
        "summary",
        compressor(provider, properties).summarize("", List.of(), 100, new Prompt("owner")));
    assertEquals(Map.of("enable_thinking", true, "format", "original"), template);
    assertEquals(Map.of("chat_template_kwargs", template), base.getExtraBody());
  }

  static Stream<Object> malformedTemplates() {
    return Stream.of("not-an-object", Map.of(7, "not-a-string-key"));
  }

  @ParameterizedTest
  @MethodSource("malformedTemplates")
  void rejectsMalformedTemplateBeforeStartingTheModel(Object template) {
    var model = mock(ChatModel.class);
    var provider = mock(LlmModelProvider.class);
    when(provider.chatModel("chat")).thenReturn(model);
    when(provider.chatOptions("chat", null))
        .thenReturn(
            OpenAiChatOptions.builder()
                .extraBody(Map.of("chat_template_kwargs", template))
                .build());
    var properties = new ContextCompressionProperties();
    properties.setSummaryEnableThinking(false);
    assertThrows(
        ContextCompressionException.class,
        () -> compressor(provider, properties).summarize("", List.of(), 100, new Prompt("owner")));
    verify(model, never()).stream(any(Prompt.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sdkOutputLimitRepairKeepsHardCapAndOriginalInputAndChargesBothCalls(
      boolean completionTokens) throws Exception {
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          boolean first = requests.size() == 1;
          String text = first ? "incomplete draft must never be stored" : "REQ-17: migration pending";
          int tokens = first ? 3000 : 100;
          String chunks =
              """
              data: {"id":"repair","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","content":"%s"},"finish_reason":null}]}

              data: {"id":"repair","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":"%s"}]}

              data: {"id":"repair","object":"chat.completion.chunk","created":1,"model":"test","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":%d,"total_tokens":%d}}

              data: [DONE]

              """.formatted(text, first ? "length" : "stop", tokens, tokens + 10);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            body.write(chunks.getBytes(StandardCharsets.UTF_8));
          }
        });
    server.start();
    try {
      var builder = OpenAiChatOptions.builder()
          .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
          .apiKey("test").model("test").maxRetries(0);
      if (completionTokens) builder.maxCompletionTokens(16000);
      else builder.maxTokens(16000);
      var base = builder.build();
      var provider = mock(LlmModelProvider.class);
      when(provider.chatModel("chat")).thenReturn(OpenAiChatModel.builder().options(base).build());
      when(provider.chatOptions("chat", null)).thenReturn(base);
      var properties = new ContextCompressionProperties();
      properties.setSummaryEnableThinking(false);
      var compressor = compressor(provider, properties);
      var budget = mock(dev.mikoto2000.rei.llm.ModelCallBudget.class);
      var original = new UserMessage("Generated user decision REQ-17: migration remains pending.");
      var result = SummaryInputPages.summarize(
          (previous, fragments, hardLimit, target, timeout) -> compressor.summarizeResult(
              previous, fragments, hardLimit, new Prompt("owner"), budget, timeout, target),
          TokenEstimator.tokenizerAware(), "existing user constraint", List.of(original),
          12000, 24000, () -> {}, java.time.Duration.ofSeconds(10));
      assertEquals("REQ-17: migration pending", result.text());
      assertEquals(100, result.outputTokens());
      assertEquals(2, requests.size());
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      var first = json.readTree(requests.get(0));
      var repair = json.readTree(requests.get(1));
      for (var request : List.of(first, repair)) {
        assertEquals(3000, request.get(completionTokens ? "max_completion_tokens" : "max_tokens").asInt());
        assertFalse(request.has(completionTokens ? "max_tokens" : "max_completion_tokens"));
        assertFalse(request.get("chat_template_kwargs").get("enable_thinking").asBoolean());
        assertEquals("none", request.get("tool_choice").asString());
        assertFalse(request.has("tools"));
      }
      org.junit.jupiter.api.Assertions.assertTrue(
          first.get("messages").get(0).get("content").asString().contains("at most 750 tokens"));
      org.junit.jupiter.api.Assertions.assertTrue(
          repair.get("messages").get(0).get("content").asString().contains("at most 375 tokens"));
      assertEquals(first.get("messages").get(1), repair.get("messages").get(1));
      assertFalse(repair.get("messages").get(1).get("content").asString().contains("incomplete draft"));
      assertEquals("Generated user decision REQ-17: migration remains pending.", original.getText());
      verify(budget, org.mockito.Mockito.times(2)).run();
      verify(budget).recordTotalTokens(3010);
      verify(budget).recordTotalTokens(110);
      if (completionTokens) assertEquals(16000, base.getMaxCompletionTokens());
      else assertEquals(16000, base.getMaxTokens());
    } finally {
      server.stop(0);
    }
  }

  private static LlmConversationCompressor compressor(
      LlmModelProvider provider, ContextCompressionProperties properties) {
    ObjectProvider<LlmModelProvider> models = mock();
    when(models.getObject()).thenReturn(provider);
    return new LlmConversationCompressor(models, properties);
  }
}
