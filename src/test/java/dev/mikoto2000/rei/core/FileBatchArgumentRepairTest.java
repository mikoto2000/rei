package dev.mikoto2000.rei.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

class FileBatchArgumentRepairTest {
  @TempDir Path root;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oversizedReadIsCorrectedBeforeCallbackAndRunContinues(boolean guarded) throws Exception {
    var reads = new AtomicInteger();
    var tools =
        new Tools() {
          @Override
          List<ReadFileResult> readMultiFile(List<ReadFileRequest> files)
              throws java.io.IOException {
            reads.incrementAndGet();
            return super.readMultiFile(files, root);
          }
        };
    for (int i = 0; i < 30; i++) Files.writeString(root.resolve(i + ".txt"), "file " + i);
    var factory = new AgentEventFactory(Clock.systemUTC());
    var context =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 20),
            new ProgressEvaluator(root, null),
            factory,
            event -> {});
    context.setRunContext(new AgentRunContext("run", "session", root, "project"));
    if (guarded)
      context.setToolPermissionGuard(
          new ToolPermissionGuard(
              new ToolPermissionPolicy(
                  new ToolPermissionProperties(
                      true, Set.of(ActionCapability.READ), Set.of(), Map.of())),
              factory,
              event -> {}));
    var callback =
        Arrays.stream(DeliveryToolRegistration.provider(tools).getToolCallbacks())
            .filter(c -> c.getToolDefinition().name().equals("readMultiFile"))
            .findFirst()
            .orElseThrow();
    var calls = new AtomicInteger();
    var model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            throw new AssertionError();
          }

          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            int step = calls.incrementAndGet();
            if (step == 1) return Flux.just(request("oversized", 31));
            var feedback =
                prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .reduce((a, b) -> b)
                    .orElseThrow()
                    .getResponses()
                    .getLast()
                    .responseData();
            if (step == 2) {
              assertThat(reads).hasValue(0);
              var correction = JsonMapper.builder().build().readTree(feedback);
              assertThat(correction.path("error").asString()).isEqualTo("INVALID_TOOL_ARGUMENTS");
              assertThat(correction.path("executed").asBoolean()).isFalse();
              assertThat(correction.path("guidance").asString()).contains("30", "Split");
              return Flux.just(request("corrected", 30));
            }
            assertThat(step).isEqualTo(3);
            assertThat(reads).hasValue(1);
            assertThat(feedback).contains("file 29");
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    var result =
        new StagnationChatModel(model)
            .call(
                new Prompt(
                    List.of(new UserMessage("review")),
                    ToolCallingChatOptions.builder()
                        .toolCallbacks(List.of(callback))
                        .toolContext(Map.of(RunExecutionContext.KEY, context))
                        .build()));
    assertThat(result.getResult().getOutput().getText()).isEqualTo("done");
    assertThat(context.budgetSnapshot().argumentCorrections()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {22, 30})
  void originalTwentyTwoFileRequestAndNewBoundaryAreAccepted(int count) throws Exception {
    var requests =
        IntStream.range(0, count)
            .mapToObj(
                i -> {
                  try {
                    Files.writeString(root.resolve(i + ".txt"), "content");
                  } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                  }
                  return new Tools.ReadFileRequest(i + ".txt", null, null);
                })
            .toList();
    var tools = new Tools();
    assertThat(tools.readMultiFile(requests, root))
        .hasSize(count)
        .allSatisfy(result -> assertThat(result.error()).isNull());
    assertThatThrownBy(
            () -> tools.readMultiFile(java.util.Collections.nCopies(31, requests.getFirst()), root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max 30");
  }

  private static ChatResponse request(String id, int count) {
    String input =
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "files",
                    IntStream.range(0, count).mapToObj(i -> Map.of("path", i + ".txt")).toList()));
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(id, "function", "readMultiFile", input)))
                    .build())));
  }
}
