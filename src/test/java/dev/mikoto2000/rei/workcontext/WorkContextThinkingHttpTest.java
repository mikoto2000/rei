package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Verify the SDK wire request, including a server default that enables reasoning. */
@Tag("integration")
class WorkContextThinkingHttpTest {
  @ParameterizedTest
  @NullSource
  @ValueSource(booleans = {false, true})
  void sendsOnlyExplicitThinkingOverrideAndParsesOnlyAnswerContent(Boolean thinking)
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
            data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"private analysis"},"finish_reason":null}]}

            data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"content":CONTENT},"finish_reason":null}]}

            data: {"id":"chat","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;
            chunks =
                chunks.replace(
                    "CONTENT",
                    tools.jackson.databind.json.JsonMapper.builder()
                        .build()
                        .writeValueAsString("{\"changes\":[]}"));
            body.write(chunks.getBytes(StandardCharsets.UTF_8));
          }
        });
    server.start();
    try {
      var base =
          OpenAiChatOptions.builder()
              .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
              .apiKey("test")
              .model("test")
              .maxRetries(0)
              .maxTokens(16384)
              .extraBody(Map.of("chat_template_kwargs", Map.of("format", "original")))
              .build();
      var models = mock(LlmModelProvider.class);
      when(models.memoryChatModel()).thenReturn(OpenAiChatModel.builder().options(base).build());
      when(models.chatOptions("memory", null)).thenReturn(base);
      var properties =
          new WorkContextProperties(
              false, true, 1200, 12000, 240, 20, 4000, 16, "memory", 0, 60, 4096, thinking);
      var evidence =
          new WorkContext.Evidence(
              "source",
              WorkContext.Origin.USER,
              "session",
              "turn",
              "run",
              null,
              null,
              null,
              Instant.EPOCH,
              Instant.EPOCH,
              "Record progress");
      assertTrue(
          new LlmWorkContextExtractor(models, properties)
              .extract(List.of(evidence), List.of(), "COMPLETED")
              .isEmpty());
      var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(request.get());
      var template = json.get("chat_template_kwargs");
      assertEquals("original", template.get("format").asString());
      if (thinking == null) assertFalse(template.has("enable_thinking"));
      else assertEquals(thinking, template.get("enable_thinking").asBoolean());
      assertEquals(4096, json.get("max_tokens").asInt());
      assertFalse(
          base.getExtraBody().get("chat_template_kwargs").toString().contains("enable_thinking"));
    } finally {
      server.stop(0);
    }
  }
}
