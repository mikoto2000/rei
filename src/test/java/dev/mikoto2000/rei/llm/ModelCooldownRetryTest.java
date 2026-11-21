package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.BoundedToolLoop;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

class ModelCooldownRetryTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void bothRetryLoopsWaitForEachCooldownBeforeSending(boolean bounded) {
    var clock = Clock.systemUTC();
    var cooldown = Duration.ofMillis(250);
    var delegate = mock(ChatModel.class);
    var calls = new AtomicInteger();
    var lastFailure = new AtomicLong();
    when(delegate.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          if (calls.incrementAndGet() > 1)
            assertTrue(System.nanoTime() - lastFailure.get() >= cooldown.toNanos());
          if (calls.get() <= 2) {
            lastFailure.set(System.nanoTime());
            return Flux.error(
                new com.openai.errors.OpenAIIoException(
                    "timeout", new java.net.SocketTimeoutException()));
          }
          return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("OK")))));
        });
    var events = new ArrayList<AgentEvent>();
    var factory = new AgentEventFactory(clock);
    var model =
        new AgentEventChatModel(
            "chat",
            delegate,
            factory,
            events::add,
            null,
            run -> false,
            new ModelOutageGuard(clock, cooldown));
    var owner = new AgentRunContext("run", "session", root, "project");
    if (bounded) {
      var retries = new BoundedToolLoop.ModelRetries(2);
      var outcome =
          new BoundedToolLoop()
              .runWithHistory(
                  model, new Prompt("work"), new AtomicInteger(3), owner, () -> {}, null, retries)
              .block(Duration.ofSeconds(10));
      assertEquals("OK", outcome.output());
      assertEquals(2, retries.attempts());
    } else {
      var budget = new OutputLimitRunBudget(3, 5);
      var execution =
          new RunExecutionContext("run", budget, new ProgressEvaluator(root), factory, events::add);
      execution.setRunContext(owner);
      execution.consumeNextLlmCall();
      var prompt =
          new Prompt(
              "work",
              ToolCallingChatOptions.builder()
                  .toolContext(Map.of(RunExecutionContext.KEY, execution))
                  .build());
      var result =
          new StagnationChatModel(model, null, 2).stream(prompt).blockLast(Duration.ofSeconds(10));
      assertEquals("OK", result.getResult().getOutput().getText());
      assertEquals(3, budget.snapshot().llmCalls());
    }
    assertEquals(3, calls.get());
    assertEquals(
        3, events.stream().filter(e -> e.type() == AgentEventType.LLM_REQUEST_STARTED).count());
    assertEquals(
        2, events.stream().filter(e -> e.type() == AgentEventType.LLM_REQUEST_FAILED).count());
  }
}
