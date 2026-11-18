package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

@Tag("integration")
class StreamTimeoutRetryHttpTest {
  @TempDir Path root;

  @Test
  void actualSdkRetriesStalledSseBodyAfterReceivingText() throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          boolean first = calls.incrementAndGet() == 1;
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            String value = first ? "discard" : "success";
            String chunk =
                "data: {\"id\":\"chat\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\""
                    + value
                    + "\"},\"finish_reason\":null}]}\n\n";
            body.write(chunk.getBytes(StandardCharsets.UTF_8));
            body.flush();
            if (first) {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            } else {
              body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
          } catch (java.io.IOException disconnected) {
            // The timed-out first attempt is expected to close its socket.
          }
        });
    server.start();
    try {
      var options =
          OpenAiChatOptions.builder()
              .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
              .apiKey("test")
              .model("test")
              .maxRetries(0)
              .build();
      var model =
          OpenAiChatModel.builder()
              .options(options)
              .httpClientBuilderCustomizer(
                  builder ->
                      builder.interceptor(new ChatStreamTimeoutInterceptor(Duration.ofMillis(200))))
              .build();
      var budget = new OutputLimitRunBudget(2, 5);
      var context =
          new RunExecutionContext(
              "run",
              budget,
              new ProgressEvaluator(root),
              new AgentEventFactory(Clock.systemUTC()),
              event -> {});
      context.setRunContext(new AgentRunContext("run", "session", root, "project"));
      context.consumeNextLlmCall();
      var promptOptions =
          options.mutate().toolContext(Map.of(RunExecutionContext.KEY, context)).build();
      var chunks =
          new StagnationChatModel(model, null, 2)
              .stream(new Prompt("work", promptOptions))
                  .collectList()
                  .block(Duration.ofSeconds(20));
      assertThat(calls.get()).isEqualTo(2);
      assertThat(chunks).anyMatch(chunk -> ToolLoopSupport.streamRetryAttempt(chunk) == 1);
      assertThat(chunks.getLast().getResult().getOutput().getText()).isEqualTo("success");
      assertThat(budget.snapshot().llmCalls()).isEqualTo(2);
    } finally {
      server.stop(0);
      executor.close();
    }
  }
}
