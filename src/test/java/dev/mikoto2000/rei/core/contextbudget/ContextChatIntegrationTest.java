package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.core.working.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import reactor.core.publisher.Flux;

class ContextChatIntegrationTest {
  @TempDir Path dir;
  @Test void realAdvisorAndModelChainSendSummaryRecentWorkingSetAndCurrentInput() {
    var turns = new ConversationTurnStore(dir);
    var old = new AgentRunContext("old", "chat", dir);
    turns.start(old, "historic requirement ".repeat(500));
    turns.finish(old, ConversationTurnStore.Status.COMPLETED, "recent answer");
    var owner = new AgentRunContext("current", "chat", dir);
    var events = new AgentEventFactory(Clock.systemUTC());
    List<AgentEvent> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
    var execution = new RunExecutionContext("current", new OutputLimitRunBudget(2, 10), mock(ProgressEvaluator.class), events, observed::add);
    execution.setRunContext(owner);
    var summaries = new ConversationSummaryRepository(dir);
    var props = new ContextCompressionProperties();
    props.setThreshold(400); props.setHardLimit(800); props.setRecentTokens(80); props.setSummaryTokens(100);
    var tokens = TokenEstimator.conservative();
    var assembler = new ContextAssembler(props, tokens, summaries,
        new ToolResultCompressor(new RawToolResultStore(dir), tokens, 1000, 200),
        (previous, messages, budget, request) -> "summary of earlier decisions", events, observed::add);
    var working = new WorkingSet(20, Clock.systemUTC());
    working.recordRead(dir.resolve("edited.java"));
    assembler.setWorkingSet(working::renderForPrompt);
    var sent = new AtomicReference<Prompt>();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        sent.set(p);
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
      }
    };
    var memory = MessageWindowChatMemory.builder().maxMessages(100).build();
    var client = ChatClient.builder(new StagnationChatModel(model, assembler)).defaultSystem("system rules")
        .defaultAdvisors(RunScopedAdvisor.wrap(List.of(new ContextHistoryAdvisor(turns, memory, summaries),
            new WorkingSetAdvisor(working)))).build();
    var options = OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, execution)).build();
    client.prompt(new Prompt(new UserMessage("current request"), options)).advisors(a -> a
        .param(AgentRunContext.class.getName(), owner).param(ChatMemory.CONVERSATION_ID, "chat"))
        .stream().chatResponse().blockLast();
    assertThat(sent.get().getContents()).contains("system rules", "summary of earlier decisions", "recent answer", "edited.java", "current request")
        .doesNotContain("historic requirement historic requirement");
    assertThat(observed).extracting(AgentEvent::type).contains(AgentEventType.CONTEXT_COMPRESSION_STARTED, AgentEventType.CONTEXT_COMPRESSION_COMPLETED);
    assertThat(observed).allMatch(e -> "current".equals(e.runId()) && "chat".equals(e.sessionId()));
    assertThat(turns.read("chat").getFirst().request()).contains("historic requirement");
  }
}
