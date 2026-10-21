package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunScopedAdvisor;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.core.working.WorkingSetAdvisor;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
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

/** 文脈チャット外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ContextChatIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 要約直近作業中集合を送信することを確認する。 */
  @Test
  void realAdvisorAndModelChainSendSummaryRecentWorkingSetAndCurrentInput() {
    var turns = new ConversationTurnStore(dir);
    var old = new AgentRunContext("old", "chat", dir);
    turns.start(old, "historic requirement ".repeat(500));
    turns.finish(old, ConversationTurnStore.Status.COMPLETED, "recent answer");
    var owner = new AgentRunContext("current", "chat", dir);
    var events = new AgentEventFactory(Clock.systemUTC());
    List<AgentEvent> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
    var execution =
        new RunExecutionContext(
            "current",
            new OutputLimitRunBudget(2, 10),
            mock(ProgressEvaluator.class),
            events,
            observed::add);
    execution.setRunContext(owner);
    var summaries = new ConversationSummaryRepository(dir);
    var props = new ContextCompressionProperties();
    props.setThreshold(400);
    props.setHardLimit(800);
    props.setRecentTokens(80);
    props.setSummaryTokens(100);
    var tokens = TokenEstimator.conservative();
    var assembler =
        new ContextAssembler(
            props,
            tokens,
            summaries,
            new ToolResultCompressor(new RawToolResultStore(dir), tokens, 1000, 200),
            (previous, messages, budget, request) -> "summary of earlier decisions",
            events,
            observed::add);
    var working = new WorkingSet(20, Clock.systemUTC());
    working.recordRead(dir.resolve("edited.java"));
    assembler.setWorkingSet(working::renderForPrompt);
    var sent = new AtomicReference<Prompt>();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、モデルへ渡す文脈の管理で参照する選択可能な設定を返す。 */
          @Override
          public OpenAiChatOptions getOptions() {
            return OpenAiChatOptions.builder().build();
          }

          /** 検証用に、モデルへ渡す文脈の管理の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            sent.set(p);
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    var memory = MessageWindowChatMemory.builder().maxMessages(100).build();
    var client =
        ChatClient.builder(new StagnationChatModel(model, assembler))
            .defaultSystem("system rules")
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultAdvisors(
                RunScopedAdvisor.wrap(
                    List.of(
                        new ContextHistoryAdvisor(turns, memory, summaries),
                        new WorkingSetAdvisor(working))))
            .build();
    var options =
        OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, execution)).build();
    client
        .prompt(new Prompt(new UserMessage("current request"), options))
        .advisors(
            a ->
                a.param(AgentRunContext.class.getName(), owner)
                    .param(ChatMemory.CONVERSATION_ID, "chat"))
        .stream()
        .chatResponse()
        .blockLast();
    assertThat(sent.get().getContents())
        .contains(
            "system rules",
            "summary of earlier decisions",
            "recent answer",
            "edited.java",
            "current request")
        .doesNotContain("historic requirement historic requirement");
    assertThat(observed)
        .extracting(AgentEvent::type)
        .contains(
            AgentEventType.CONTEXT_COMPRESSION_STARTED,
            AgentEventType.CONTEXT_COMPRESSION_COMPLETED);
    assertThat(observed).allMatch(e -> "current".equals(e.runId()) && "chat".equals(e.sessionId()));
    assertThat(turns.read("chat").getFirst().request()).contains("historic requirement");
  }
}
