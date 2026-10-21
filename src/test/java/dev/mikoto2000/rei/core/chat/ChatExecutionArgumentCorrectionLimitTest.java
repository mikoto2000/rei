package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** チャット実行引数補正上限の結果と状態の契約を検証するテスト。 */
class ChatExecutionArgumentCorrectionLimitTest {
  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @ParameterizedTest
  @ValueSource(ints = {3, 0})
  void configuredCorrectionLimitReachesTheRealToolLoopAndActionableTerminalMessage(int limit) {
    var calls = new AtomicInteger();
    var effects = new AtomicInteger();
    var remaining = new ArrayList<Integer>();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            int invocation = calls.incrementAndGet();
            if (invocation > limit + 1)
              throw new AssertionError("Configured argument limit was not enforced");
            prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((first, last) -> last)
                .ifPresent(
                    message -> {
                      var correction =
                          tools.jackson.databind.json.JsonMapper.builder()
                              .build()
                              .readTree(message.getResponses().getFirst().responseData());
                      assertThat(correction.path("maxCorrections").asInt()).isEqualTo(limit);
                      remaining.add(correction.path("correctionsRemaining").asInt());
                    });
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                    List.of(
                                        new AssistantMessage.ToolCall(
                                            "call-" + invocation,
                                            "function",
                                            "delegateTask",
                                            "{}")))
                                .build()))));
          }
        };
    ToolCallback tool =
        new ToolCallback() {
          /** 検証用に、会話の実行で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("delegateTask")
                .description("delegate")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            effects.incrementAndGet();
            return "unexpected execution";
          }
        };
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultToolCallbacks(tool)
            .build();
    var properties = new LlmProperties();
    properties.getOutputLimit().setMaxToolArgumentCorrectionsPerRun(limit);
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var events = new ArrayList<AgentEvent>();
    var service =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            properties,
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Clock.systemUTC(),
            Optional.empty(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add);
    var approvals = mock(ToolApprovalRepository.class);
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
            new AgentEventFactory(Clock.systemUTC()),
            events::add);
    guard.setApprovals(approvals);
    service.setToolPermissionGuard(guard);
    var result =
        service.execute(
            new AgentRunContext("run", "session", Path.of("."), "project"),
            "ChatGPT にレビューして",
            new UserInterventionQueue());
    assertThat(result.stopCode()).isEqualTo("tool_argument_repair_exhausted");
    assertThat(result.errorMessage())
        .contains(limit + "回", "rei.llm.output-limit.max-tool-argument-corrections-per-run");
    assertThat(calls).hasValue(limit + 1);
    assertThat(effects).hasValue(0);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(approvals);
    assertThat(remaining)
        .containsExactlyElementsOf(
            java.util.stream.IntStream.iterate(limit - 1, value -> value >= 0, value -> value - 1)
                .boxed()
                .toList());
    assertThat(events.stream().filter(event -> event.type() == AgentEventType.AGENT_RUN_FAILED))
        .hasSize(1);
  }
}
