package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.RunScopedAdvisor;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** 外部エージェント評価外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ExternalAgentEvaluationIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 外部エージェントへの委譲で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void naturalLanguageReturnsToReiAndDoesNotPersistRawLogs() {
    evaluate(false, false);
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void slashDirectlyDelegatesAndReturnsToRei() {
    evaluate(true, false);
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void externalFailureStillGeneratesReiAnswer() {
    evaluate(true, true);
  }

  /** {@code evaluate} の条件を再現するため、検証用の操作や状態を扱う。 */
  void evaluate(boolean slash, boolean failed) {
    String user = slash ? "/agent codex review" : "Codex にこの設計をレビューさせて";
    String expected = failed ? "Codex CLI unavailable" : "Evidence to evaluate";
    String projectId = UUID.randomUUID().toString();
    var owner = new AgentRunContext("run", "project:" + projectId + ":chat:main", root, projectId);
    var factory = new AgentEventFactory(Clock.systemUTC());
    var run =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 8),
            new ProgressEvaluator(root, null),
            factory,
            e -> {});
    run.setRunContext(owner);
    run.setUserRequest(user);
    var invocations = new AtomicInteger();
    var memory = MessageWindowChatMemory.builder().maxMessages(20).build();
    memory.add(owner.conversationId(), new AssistantMessage("Decision: require HTTPS"));
    var service =
        new ExternalAgentDelegationService(
            (request, cancelled) -> {
              invocations.incrementAndGet();
              assertTrue(request.context().contains("HTTPS"));
              return new ExternalAgentResult(
                  failed
                      ? ExternalAgentResult.Status.UNAVAILABLE
                      : ExternalAgentResult.Status.SUCCESS,
                  expected,
                  List.of(),
                  List.of(),
                  1,
                  0,
                  "RAW PROCESS LOG MUST NOT PERSIST");
            },
            new CommandCancellationService(),
            factory,
            e -> {},
            Optional.empty());
    var calls = new AtomicInteger();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、外部エージェントへの委譲で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
          }

          /** 検証用に、外部エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            if (!slash && calls.getAndIncrement() == 0) {
              var call =
                  new AssistantMessage.ToolCall(
                      "review",
                      "function",
                      "requestCodexReview",
                      "{\"task\":\"design\",\"context\":\"required HTTPS\"}");
              return Flux.just(
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .content("")
                                  .toolCalls(List.of(call))
                                  .build()))));
            }
            assertTrue(
                prompt.getInstructions().toString().contains(expected),
                prompt.getInstructions().toString());
            assertFalse(prompt.getInstructions().toString().contains("RAW PROCESS LOG"));
            return Flux.just(
                new ChatResponse(
                    List.of(new Generation(new AssistantMessage("rei independent evaluation")))));
          }
        };
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor())
            .defaultTools(new ExternalAgentTools(service))
            .defaultAdvisors(
                RunScopedAdvisor.wrap(
                    List.of(
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        new ExternalAgentReviewAdvisor(service, memory))))
            .build();
    try (var scope = AgentRunScope.open(owner)) {
      var options =
          OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, run)).build();
      var response =
          client
              .prompt(new Prompt(new UserMessage(user), options))
              .advisors(
                  a ->
                      a.param(ChatMemory.CONVERSATION_ID, owner.conversationId())
                          .param(AgentRunContext.class.getName(), owner))
              .stream()
              .content()
              .collectList()
              .block(Duration.ofSeconds(10));
      assertTrue(String.join("", response).contains("rei independent evaluation"));
    }
    assertEquals(1, invocations.get());
    assertTrue(
        memory.get(owner.conversationId()).stream()
            .anyMatch(m -> m.getText().contains("rei independent evaluation")),
        memory.get(owner.conversationId()).toString());
    assertFalse(
        memory.get(owner.conversationId()).stream()
            .anyMatch(m -> m.getText().contains("RAW PROCESS LOG")));
  }
}
