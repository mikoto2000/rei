package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Flux;

class ListFilePathRecoveryTest {
  @Test
  void projectPathRejectionReachesModelAndCorrectedCallSucceedsInSameRun() {
    var callback =
        Arrays.stream(
                MethodToolCallbackProvider.builder()
                    .toolObjects(new Tools())
                    .build()
                    .getToolCallbacks())
            .filter(tool -> tool.getToolDefinition().name().equals("listFile"))
            .findFirst()
            .orElseThrow();
    var context =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 20),
            new ProgressEvaluator(Path.of(".")),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    var prompt =
        new Prompt(
            "List project files",
            ToolCallingChatOptions.builder()
                .toolCallbacks(callback)
                .toolContext(Map.of(RunExecutionContext.KEY, context))
                .build());
    var turns = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt request) {
            return switch (turns.getAndIncrement()) {
              case 0 -> listFiles("outside", "..");
              case 1 -> {
                assertThat(lastToolResult(request))
                    .contains("PROJECT_PATH_EXCLUDED", "\"accepted\":false", "readSkillResource");
                yield listFiles("inside", ".");
              }
              case 2 -> {
                assertThat(lastToolResult(request)).contains("pom.xml");
                yield new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
              }
              default -> throw new AssertionError("Unexpected model call");
            };
          }

          @Override
          public Flux<ChatResponse> stream(Prompt request) {
            return Flux.just(call(request));
          }
        };

    new StagnationChatModel(model).stream(prompt).blockLast(Duration.ofSeconds(15));
    assertThat(turns.get()).isEqualTo(3);
    assertThat(context.budgetSnapshot().argumentCorrections()).isEqualTo(1);
  }

  private static ChatResponse listFiles(String id, String baseDir) {
    var call =
        new AssistantMessage.ToolCall(
            id, "function", "listFile", "{\"baseDir\":\"" + baseDir + "\"}");
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())));
  }

  private static String lastToolResult(Prompt request) {
    return ((ToolResponseMessage) request.getInstructions().getLast())
        .getResponses()
        .getFirst()
        .responseData();
  }
}
