package dev.mikoto2000.rei.externalagent;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class ExternalAgentEvaluationIntegrationTest {
  @TempDir Path root;
  @Test void naturalLanguageReturnsToReiAndDoesNotPersistRawLogs() { evaluate(false, false); }
  @Test void slashDirectlyDelegatesAndReturnsToRei() { evaluate(true, false); }
  @Test void externalFailureStillGeneratesReiAnswer() { evaluate(true, true); }
  void evaluate(boolean slash, boolean failed) {
    String user = slash ? "/agent codex review" : "Codex にこの設計をレビューさせて";
    String expected = failed ? "Codex CLI unavailable" : "Evidence to evaluate";
    String projectId = UUID.randomUUID().toString();
    var owner = new AgentRunContext("run", "project:" + projectId + ":chat:main", root, projectId);
    var factory = new AgentEventFactory(Clock.systemUTC());
    var run = new RunExecutionContext("run", new OutputLimitRunBudget(2, 8), new ProgressEvaluator(root, null), factory, e -> {});
    run.setRunContext(owner); run.setUserRequest(user);
    var invocations = new AtomicInteger();
    var memory = MessageWindowChatMemory.builder().maxMessages(20).build();
    memory.add(owner.conversationId(), new AssistantMessage("Decision: require HTTPS"));
    var service = new ExternalAgentDelegationService((request, cancelled) -> {
      invocations.incrementAndGet();
      assertTrue(request.context().contains("HTTPS"));
      return new ExternalAgentResult(failed ? ExternalAgentResult.Status.UNAVAILABLE : ExternalAgentResult.Status.SUCCESS,
          expected, List.of(), List.of(), 1, 0, "RAW PROCESS LOG MUST NOT PERSIST");
    }, new CommandCancellationService(), factory, e -> {}, Optional.empty());
    var calls = new AtomicInteger();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) {
        if (!slash && calls.getAndIncrement() == 0) {
          var call = new AssistantMessage.ToolCall("review", "function", "requestCodexReview",
              "{\"task\":\"design\",\"context\":\"required HTTPS\"}");
          return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build()))));
        }
        assertTrue(prompt.getInstructions().toString().contains(expected), prompt.getInstructions().toString());
        assertFalse(prompt.getInstructions().toString().contains("RAW PROCESS LOG"));
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("rei independent evaluation")))));
      }
    };
    var client = ChatClient.builder(new StagnationChatModel(model))
        .defaultTools(new ExternalAgentTools(service))
        .defaultAdvisors(RunScopedAdvisor.wrap(List.of(PromptChatMemoryAdvisor.builder(memory).build(),
            new ExternalAgentReviewAdvisor(service, memory)))).build();
    try (var scope = AgentRunScope.open(owner)) {
      var options = OpenAiChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, run)).build();
      var response = client.prompt(new Prompt(new UserMessage(user), options))
          .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, owner.conversationId()).param(AgentRunContext.class.getName(), owner))
          .stream().content().collectList().block(Duration.ofSeconds(10));
      assertTrue(String.join("", response).contains("rei independent evaluation"));
    }
    assertEquals(1, invocations.get());
    assertTrue(memory.get(owner.conversationId()).stream().anyMatch(m -> m.getText().contains("rei independent evaluation")), memory.get(owner.conversationId()).toString());
    assertFalse(memory.get(owner.conversationId()).stream().anyMatch(m -> m.getText().contains("RAW PROCESS LOG")));
  }
}
