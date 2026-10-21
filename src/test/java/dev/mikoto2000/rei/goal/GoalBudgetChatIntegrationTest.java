package dev.mikoto2000.rei.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.UserInterventionQueue;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

/** 目標予算チャット外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoalBudgetChatIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void goalOnlyTokenLimitIsEnforcedAtActualChatBoundaryBeforeTools() {
    var toolCalls = new AtomicInteger();
    var model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                    List.of(
                                        new AssistantMessage.ToolCall(
                                            "call", "function", "readFile", "{}")))
                                .build())),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(1, 10))
                        .build())));
    ToolCallback read =
        new ToolCallback() {
          /** 検証用に、目標の達成管理で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、目標の達成管理の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            toolCalls.incrementAndGet();
            return "read";
          }
        };
    var properties = new LlmProperties();
    properties.getOutputLimit().setMaxTotalTokensPerGoal(10);
    assertEquals(0, properties.getOutputLimit().getMaxTotalTokensPerRun());
    var goals =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC(),
            properties);
    var goal =
        goals.create(
            new AgentRunContext("source", "session", dir, "project"),
            "Artifact",
            "out.txt",
            "a".repeat(64),
            3,
            10);
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var chat =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(
                ChatClient.builder(new StagnationChatModel(model))
                    .defaultAdvisors(new RunAwareToolCallingAdvisor())
                    .defaultTools(read)
                    .build()),
            holder,
            new FixedLlmModelProvider(),
            properties,
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    var loop =
        new GoalLoopService(
            goals,
            new FileGoalVerifier(),
            (claim, run, done) ->
                done.accept(
                    new GoalLoopService.Outcome(
                        chat.execute(
                            new AgentRunContext(run, "session", dir, "project"),
                            "Artifact",
                            new UserInterventionQueue(),
                            goals.modelBudget(claim, run)))),
            new ToolPermissionProperties(true, null, null, null),
            new GoalEvents(event -> {}, Clock.systemUTC()));
    var stopped = loop.run("project", goal.id());
    assertEquals("BLOCKED", stopped.status());
    assertEquals("token_budget_exhausted", stopped.reason());
    assertEquals(11, stopped.totalTokens());
    assertEquals(0, stopped.pendingLlmCalls());
    assertEquals(0, toolCalls.get());
    assertThrows(IllegalStateException.class, () -> loop.run("project", goal.id()));
    verify(model, times(1)).stream(any(Prompt.class));
  }

  /** 目標の達成管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void actualChatToolLoopReservesEveryCallAndStopsAtDurableGoalLimit() {
    var modelCalls = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、目標の達成管理で参照する選択可能な設定を返す。 */
          @Override
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、目標の達成管理の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            int call = modelCalls.incrementAndGet();
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder()
                                .content("")
                                .toolCalls(
                                    List.of(
                                        new AssistantMessage.ToolCall(
                                            "call-" + call, "function", "readFile", "{}")))
                                .build()))));
          }
        };
    ToolCallback read =
        new ToolCallback() {
          /** 検証用に、目標の達成管理で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、目標の達成管理の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            return "unchanged";
          }
        };
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultTools(read)
            .build();
    var chat =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    var goals =
        new GoalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("goals.db")),
            Clock.systemUTC());
    var goal =
        goals.create(
            new AgentRunContext("source", "session", dir, "project"),
            "Produce artifact",
            "out.txt",
            "a".repeat(64),
            3,
            2);
    var loop =
        new GoalLoopService(
            goals,
            new FileGoalVerifier(),
            (claim, run, done) -> {
              var reservation =
                  new OutputLimitRunBudget.LlmCallReservation() {
                    /** {@code tryReserve} の条件を再現するための補助情報を返す。 */
                    public boolean tryReserve() {
                      return goals.reserveLlm(claim);
                    }

                    /** {@code remaining} の条件を再現するための補助情報を返す。 */
                    public int remaining() {
                      return goals.remainingLlm(claim);
                    }
                  };
              var result =
                  chat.execute(
                      new AgentRunContext(run, "session", dir, "project"),
                      "Produce artifact",
                      new UserInterventionQueue(),
                      reservation);
              assertEquals("llm_call_budget_exceeded", result.stopCode());
              done.accept(new GoalLoopService.Outcome(result));
            },
            new ToolPermissionProperties(true, null, null, null),
            new GoalEvents(event -> {}, Clock.systemUTC()));
    var stopped = loop.run("project", goal.id());
    assertEquals("BLOCKED", stopped.status());
    assertEquals(2, modelCalls.get());
    assertEquals(2, stopped.llmCallsUsed());
    assertEquals(1, stopped.attempts());
    assertThrows(IllegalStateException.class, () -> loop.run("project", goal.id()));
    assertEquals(2, modelCalls.get());
  }
}
