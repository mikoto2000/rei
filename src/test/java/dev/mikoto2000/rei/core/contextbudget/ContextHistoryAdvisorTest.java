package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

class ContextHistoryAdvisorTest {
  @TempDir Path dir;
  @Test void includesInterventionsAndAuxiliaryResultsFromPersistentLog() {
    var logs = new dev.mikoto2000.rei.conversation.ConversationLogStore(dir.resolve("logs"), java.time.Clock.systemUTC(),
        new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    logs.append("chat", "user", "original request");
    logs.append("chat", "user", "intervention: keep API unchanged");
    logs.append("chat", "assistant", "auxiliary URL summary");
    logs.append("chat", "user", "current request");
    var memory = MessageWindowChatMemory.builder().maxMessages(2).build();
    var advisor = new ContextHistoryAdvisor(new ConversationTurnStore(dir), memory, new ConversationSummaryRepository(dir), logs);
    var prompt = new Prompt(new UserMessage("current request"), org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    var result = advisor.before(ChatClientRequest.builder().prompt(prompt)
        .context(Map.of(ChatMemory.CONVERSATION_ID, "chat")).build(), null).prompt();
    assertThat(result.getInstructions()).extracting(Message::getText).containsExactly(
        "original request", "intervention: keep API unchanged", "auxiliary URL summary", "current request");
    assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) result.getOptions()).getToolContext())
        .containsEntry("rei.historySource", "log");
  }
  @Test void initiallyEmptyWorkingSetStillHasDedicatedRefreshSlot() {
    var working = new dev.mikoto2000.rei.core.working.WorkingSet(20, java.time.Clock.systemUTC());
    var advisor = new dev.mikoto2000.rei.core.working.WorkingSetAdvisor(working);
    var request = ChatClientRequest.builder().prompt(new Prompt(new UserMessage("raw user request")))
        .context(Map.of("rei.contextProjection", true)).build();
    var result = advisor.before(request, null).prompt();
    assertThat(result.getUserMessage().getText()).isEqualTo("raw user request");
    assertThat(result.getInstructions()).anyMatch(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.workingSet")));
  }
  @Test void historyBeyondMemoryWindowSurvivesCompressionAndRestart() {
    var turns = new ConversationTurnStore(dir);
    var memory = MessageWindowChatMemory.builder().maxMessages(2).build();
    for (int i = 0; i < 5; i++) {
      var owner = new AgentRunContext("run" + i, "chat", dir);
      turns.start(owner, "requirement " + i + " details".repeat(100));
      turns.finish(owner, ConversationTurnStore.Status.COMPLETED, "answer " + i);
      memory.add("chat", new UserMessage("requirement " + i));
      memory.add("chat", new AssistantMessage("answer " + i));
    }
    var before = turns.read("chat");
    var current = new AgentRunContext("current", "chat", dir);
    var summaries = new ConversationSummaryRepository(dir);
    var advisor = new ContextHistoryAdvisor(turns, memory, summaries);
    var request = ChatClientRequest.builder().prompt(new Prompt(new UserMessage("next request")))
        .context(Map.of(ChatMemory.CONVERSATION_ID, "chat", AgentRunContext.class.getName(), current)).build();
    var assembled = advisor.before(request, null).prompt();
    assertThat(assembled.getContents()).contains("requirement 0", "requirement 4", "next request");
    var props = new ContextCompressionProperties();
    props.setThreshold(300); props.setHardLimit(600); props.setRecentTokens(80); props.setSummaryTokens(100);
    var tokens = TokenEstimator.conservative();
    new ContextAssembler(props, tokens, summaries, new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70),
        (p, m, b, r) -> "decisions retained", null, null).assemble(assembled, "chat", "current", () -> {});
    assertThat(new ConversationTurnStore(dir).read("chat")).containsExactlyElementsOf(before);
    assertThat(memory.get("chat")).hasSizeLessThanOrEqualTo(2);
    assertThat(new ConversationSummaryRepository(dir).read("chat").throughSequence()).isGreaterThan(0);
  }
}
