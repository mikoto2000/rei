package dev.mikoto2000.rei.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

class FileBatchArgumentsTest {
  @ParameterizedTest
  @CsvSource({
    "readMultiFile,files,30", "readSkillResource,files,30", "countFileLines,paths,64",
    "grepMultiQuery,queries,20", "searchAndRead,queries,20", "beginReview,paths,64"
  })
  void requiredBatchesRejectMissingEmptyWrongTypeAndOversizedInput(
      String tool, String field, int maximum) {
    for (Object value :
        new Object[] {
          Collections.nCopies(maximum + 1, Map.of()), Collections.emptyList(), "not an array"
        }) {
      String input = input(tool, field, value);
      assertThatThrownBy(() -> ToolPermissionPolicy.validateCommonArguments(tool, input, 10))
          .isInstanceOf(InvalidToolArgumentsException.class)
          .hasMessageContaining(field)
          .hasMessageContaining("Split larger batches");
    }
    assertThatThrownBy(() -> FileBatchArguments.validate(tool, "{}"))
        .isInstanceOf(InvalidToolArgumentsException.class);
    for (int count : new int[] {1, maximum})
      assertThatCode(() ->
              FileBatchArguments.validate(
                  tool, input(tool, field, Collections.nCopies(count, Map.of()))))
          .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({"0,true", "20,true", "21,false"})
  void preferredPathsHaveTheirOwnOptionalLimit(int count, boolean valid) {
    String input =
        JsonMapper.builder()
            .build()
            .writeValueAsString(
                Map.of(
                    "request",
                    Map.of(
                        "queries",
                        Collections.singletonList(Map.of("pattern", "x")),
                        "preferredPaths",
                        Collections.nCopies(count, "file.txt"))));
    if (valid)
      assertThatCode(() -> FileBatchArguments.validate("searchAndRead", input))
          .doesNotThrowAnyException();
    else
      assertThatThrownBy(() -> FileBatchArguments.validate("searchAndRead", input))
          .isInstanceOf(InvalidToolArgumentsException.class)
          .hasMessageContaining("request.preferredPaths");
  }

  @ParameterizedTest
  @CsvSource({
    "readMultiFile,files,30", "readSkillResource,files,30", "countFileLines,paths,64",
    "grepMultiQuery,queries,20", "searchAndRead,queries,20", "beginReview,paths,64"
  })
  void oversizedBatchReturnsCorrectionWithoutExecutingCallback(String tool, String field, int max) {
    var effects = new AtomicInteger();
    var callback =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(tool).description(tool).inputSchema("{}").build();
          }

          public String call(String input) {
            effects.incrementAndGet();
            return "executed";
          }
        };
    var context =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 20),
            new ProgressEvaluator(Path.of("."), null),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    var prompt =
        new Prompt(
            List.of(),
            ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(callback))
                .toolContext(Map.of(RunExecutionContext.KEY, context))
                .build());
    String invalid = input(tool, field, Collections.nCopies(max + 1, Map.of()));
    var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall("batch", "function", tool, invalid)))
                        .build())));
    var result = new ToolLoopSupport().executeResumable(prompt, response, context);
    var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
    assertThat(feedback.getResponses().getFirst().responseData())
        .contains("INVALID_TOOL_ARGUMENTS", "\"executed\":false");
    assertThat(effects).hasValue(0);
    assertThat(context.budgetSnapshot().argumentCorrections()).isEqualTo(1);
    assertThat(ToolPermissionPolicy.supportsArgumentCorrection(tool)).isTrue();
  }

  private static String input(String tool, String field, Object value) {
    Object arguments = Map.of(field, value);
    if (tool.equals("searchAndRead")) arguments = Map.of("request", arguments);
    return JsonMapper.builder().build().writeValueAsString(arguments);
  }
}
