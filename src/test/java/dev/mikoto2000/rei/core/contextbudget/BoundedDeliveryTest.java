package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;

import dev.mikoto2000.rei.core.chat.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

class BoundedDeliveryTest {
  @TempDir Path dir;

  static class Reader {
    @Tool(description = "read")
    @ResultDelivery(ResultDelivery.Kind.FILE_CONTENT)
    String readFixture() {
      return "教材🙂".repeat(2000);
    }
  }

  @Test
  void isolatedLoopUsesSameFinalInputGuardAndRetainsStepLimit() {
    var estimator = TokenEstimator.conservative();
    var props = new ContextCompressionProperties();
    props.setHardLimit(1000);
    var assembler =
        new ContextAssembler(
            props,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 8000, 2000),
            (old, messages, budget, prompt) -> "summary",
            null,
            null);
    var calls = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          public Flux<ChatResponse> stream(Prompt prompt) {
            assertThat(ContextBudgetManager.estimateMessages(prompt.getInstructions(), estimator))
                .isLessThanOrEqualTo(1000);
            if (calls.incrementAndGet() == 1)
              return Flux.just(
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .toolCalls(
                                      List.of(
                                          new AssistantMessage.ToolCall(
                                              "read", "function", "readFixture", "{}")))
                                  .build()))));
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    var callbacks = DeliveryToolRegistration.provider(new Reader()).getToolCallbacks();
    var options = OpenAiChatOptions.builder().toolCallbacks(callbacks).build();
    var remaining = new AtomicInteger(2);
    var outcome =
        new BoundedToolLoop(assembler)
            .runWithHistory(
                model,
                new Prompt(new UserMessage("inspect"), options),
                remaining,
                new AgentRunContext("child", "subagent:child", dir),
                () -> {})
            .block();
    assertThat(outcome.output()).isEqualTo("done");
    assertThat(calls).hasValue(2);
    assertThat(remaining).hasValue(0);
  }
}
