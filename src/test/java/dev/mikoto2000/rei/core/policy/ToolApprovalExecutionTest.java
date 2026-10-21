package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
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

/** ツール承認実行の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ToolApprovalExecutionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void resumedChatExecutesExactApprovedCallbackOnce(boolean voice) {
    Clock clock = Clock.systemUTC();
    var events = new ArrayList<AgentEvent>();
    var repository =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("approvals.db")), clock);
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(!voice, Set.of(), Set.of(), Map.of())),
            new AgentEventFactory(clock),
            events::add);
    guard.setApprovals(repository);
    var modelCalls = new AtomicInteger();
    var effects = new AtomicInteger();
    var delegate =
        new ChatModel() {
          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            var message =
                modelCalls.getAndIncrement() == 0
                    ? AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "call", "function", "writeMultiFile", "{}")))
                        .build()
                    : new AssistantMessage("done");
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
          }
        };
    ToolCallback callback =
        new ToolCallback() {
          /** 検証用に、ツール利用の権限制御で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("writeMultiFile")
                .description("write")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、ツール利用の権限制御の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            effects.incrementAndGet();
            return "saved";
          }
        };
    var model = new StagnationChatModel(delegate);
    for (int attempt = 0; attempt < 3; attempt++) {
      modelCalls.set(0);
      var budget = new OutputLimitRunBudget(2, 5);
      budget.tryConsumeLlmCall();
      var context =
          new RunExecutionContext(
              "run" + attempt,
              budget,
              new ProgressEvaluator(dir),
              new AgentEventFactory(clock),
              events::add);
      var owner = new AgentRunContext("run" + attempt, "session", dir, "project");
      context.setRunContext(voice ? owner.asVoiceInput() : owner);
      context.setToolPermissionGuard(guard);
      var prompt =
          new Prompt(
              "write",
              ToolCallingChatOptions.builder()
                  .toolCallbacks(callback)
                  .toolContext(Map.of(RunExecutionContext.KEY, context))
                  .build());
      if (attempt == 1) assertDoesNotThrow(() -> model.stream(prompt).blockLast());
      else assertThrows(RuntimeException.class, () -> model.stream(prompt).blockLast());
      if (attempt == 0) {
        assertEquals(0, effects.get());
        repository.decide("project", repository.list("project").getFirst().id(), true);
      }
    }
    assertEquals(1, effects.get());
  }
}
