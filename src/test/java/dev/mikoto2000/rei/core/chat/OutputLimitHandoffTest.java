package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitReplanPlan;
import dev.mikoto2000.rei.llm.OutputLimitReplanRequest;
import dev.mikoto2000.rei.llm.OutputLimitReplanSubgoal;
import dev.mikoto2000.rei.llm.OutputLimitReplanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** 出力上限後にも、配信済み根拠と部分評価が次のタスクへ渡ることを確認する。 */
class OutputLimitHandoffTest {
  /** 巨大な本文や制御文字があっても、参考データの上限と省略表示を維持する。 */
  @Test
  void snapshotIsBoundedAndDoesNotRecursivelyCopyHandoffsOrSystemInstructions() throws Exception {
    var messages = new ArrayList<Message>();
    messages.add(new SystemMessage("private system instructions"));
    messages.add(
        UserMessage.builder()
            .text("old handoff")
            .metadata(Map.of(OutputLimitHandoff.KEY, true))
            .build());
    messages.add(
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        "read-1", "readMultiFile", "docs/a.md:1 source " + "a".repeat(30000))))
            .build());
    messages.add(
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        "read-2", "readMultiFile", "\u0001".repeat(30000))))
            .build());
    String snapshot =
        OutputLimitHandoff.capture(
            messages, "candidate, not yet verified", "job-1; pending docs/b.md");
    assertThat(snapshot).hasSizeLessThanOrEqualTo(OutputLimitHandoff.MAX_CHARS);
    assertThat(snapshot)
        .contains(
            "job-1", "pending docs/b.md", "docs/a.md:1", "omitted", "candidate, not yet verified");
    assertThat(snapshot).doesNotContain("private system instructions", "old handoff");
    assertThat(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(snapshot)
                .path("partial")
                .asBoolean())
        .isTrue();
  }

  /** 実ツールループの分割で本文を再取得せず、次の子タスクへ成果と元の依頼を引き継ぐ。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void splitterHandsDeliveredEvidenceToPlannerAndAllSubgoalsWithoutReplayingTruncatedCalls(
      boolean splitAgain) {
    var calls = new AtomicInteger();
    var reads = new AtomicInteger();
    var prompts = new ArrayList<Prompt>();
    ChatModel model =
        new ChatModel() {
          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          @Override
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            int n = calls.incrementAndGet();
            AssistantMessage output =
                switch (n) {
                  case 1 ->
                      AssistantMessage.builder()
                          .content("")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "read", "function", "readFile", "{}")))
                          .build();
                  case 2 ->
                      new AssistantMessage("candidate A; docs/a.md:12; docs/b.md still unread");
                  case 3 ->
                      AssistantMessage.builder()
                          .content("persist candidate A next")
                          .toolCalls(
                              List.of(
                                  new AssistantMessage.ToolCall(
                                      "incomplete", "function", "readFile", "{broken")))
                          .build();
                  case 4 ->
                      new AssistantMessage(
                          splitAgain
                              ? "candidate B, interrupted again"
                              : "assessed unit A; remaining docs/b.md");
                  case 5 ->
                      new AssistantMessage(
                          splitAgain ? "assessed unit A; remaining docs/b.md" : "assessed unit B");
                  case 6 -> new AssistantMessage(splitAgain ? "assessed unit B" : "final review");
                  default -> new AssistantMessage("final review");
                };
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            output,
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason(
                                    n == 1
                                        ? "tool_calls"
                                        : n < 4 || splitAgain && n == 4 ? "length" : "stop")
                                .build()))));
          }
        };
    ToolCallback read =
        new ToolCallback() {
          @Override
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readFile")
                .description("read source")
                .inputSchema("{}")
                .build();
          }

          @Override
          public String call(String input) {
            reads.incrementAndGet();
            return "docs/a.md:12 delivered evidence; revision v1";
          }
        };
    var planner = mock(OutputLimitReplanner.class);
    when(planner.replan(any(OutputLimitReplanRequest.class)))
        .thenReturn(
            new OutputLimitReplanPlan(
                List.of(
                    new OutputLimitReplanSubgoal("a", "assess unit A"),
                    new OutputLimitReplanSubgoal("b", "assess unit B")),
                "integrate saved assessments"));
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor())
            .defaultTools(
                read,
                protocolTool("beginReview"),
                protocolTool("readReviewFile"),
                protocolTool("submitReviewPage"))
            .build();
    var service =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.of(planner));
    var result = service.execute("Review all docs; do not edit materials");
    assertThat(result.success()).isTrue();
    assertThat(result.text()).isEqualTo("final review");
    assertThat(reads).hasValue(1);
    assertThat(calls).hasValue(splitAgain ? 9 : 6);
    assertThat(prompts.getFirst().getContents())
        .contains("persist its", "before bulk-reading the next unit");
    var request = ArgumentCaptor.forClass(OutputLimitReplanRequest.class);
    verify(planner, org.mockito.Mockito.times(splitAgain ? 2 : 1)).replan(request.capture());
    for (var replan : request.getAllValues())
      assertThat(replan.progressSoFar())
          .contains("delivered evidence", "candidate A", "docs/b.md still unread");
    for (int index = 3; index < (splitAgain ? 9 : 6); index++) {
      assertThat(prompts.get(index).getContents())
          .contains("delivered evidence", "candidate A", "Review all docs; do not edit materials");
      assertThat(
              prompts.get(index).getInstructions().stream()
                  .filter(AssistantMessage.class::isInstance)
                  .map(AssistantMessage.class::cast)
                  .flatMap(m -> m.getToolCalls().stream()))
          .isEmpty();
    }
    assertThat(prompts.get(splitAgain ? 5 : 4).getContents())
        .contains("assessed unit A; remaining docs/b.md");
    // Run-local context cannot leak into a later user's request.
    service.execute("unrelated question");
    assertThat(prompts.getLast().getContents()).doesNotContain("delivered evidence", "candidate A");
  }

  /** プロトコルが選択可能な通常 Run の案内を検証するための定義。 */
  private static ToolCallback protocolTool(String name) {
    var callback = mock(ToolCallback.class);
    when(callback.getToolDefinition())
        .thenReturn(
            ToolDefinition.builder()
                .name(name)
                .description("review protocol")
                .inputSchema("{}")
                .build());
    return callback;
  }

  @Test
  void capturesOneNullableTextSnapshotPerDeliveredMessage() {
    var assistant = mock(AssistantMessage.class);
    when(assistant.getMetadata()).thenReturn(Map.of());
    when(assistant.getText()).thenReturn("saved note", null);
    var system = mock(SystemMessage.class);
    when(system.getMetadata()).thenReturn(Map.of());
    when(system.getText()).thenReturn(null);
    assertThat(OutputLimitHandoff.capture(List.of(assistant, system), "", ""))
        .contains("saved note");
    verify(assistant).getText();
    verify(system).getText();
  }
}
