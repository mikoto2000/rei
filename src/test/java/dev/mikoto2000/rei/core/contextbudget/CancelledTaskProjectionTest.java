package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

@Tag("integration")
class CancelledTaskProjectionTest {
  @TempDir Path dir;
  private final Instant time = Instant.parse("2026-10-26T12:00:00Z");

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedRequestCancelledGreetingAndGuidanceStayArchivedAfterRestart(boolean sqlite)
      throws Exception {
    if (sqlite)
      try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(dir)) {
        migration.prepare();
      }
    ConversationTurnStore turns =
        sqlite ? new SqliteConversationTurnStore(dir) : new ConversationTurnStore(dir);
    var logs = logs();
    finish(
        turns,
        logs,
        "done",
        "unrelated completed question",
        ConversationTurnStore.Status.COMPLETED,
        0);
    finish(
        turns, logs, "failed", "inspect secret-image.png", ConversationTurnStore.Status.FAILED, 10);
    var cancelled = owner("cancelled");
    turns.start(cancelled, "hello", time.plusSeconds(20));
    append("user", "hello", 21);
    append("user", "old intervention", 22);
    append("assistant", "continuing secret-image.png", 23);
    turns.finish(cancelled, ConversationTurnStore.Status.CANCELLED, "continuing secret-image.png");
    turns = sqlite ? new SqliteConversationTurnStore(dir) : new ConversationTurnStore(dir);
    var saved = turns.read("chat");
    var summaries = new ConversationSummaryRepository(dir);
    summaries.save(
        ConversationSummaryRepository.runKey("chat", "cancelled"),
        new ConversationSummary("cancelled tool observations", 1, Instant.now()));
    var advisor =
        new ContextHistoryAdvisor(
            turns, MessageWindowChatMemory.builder().build(), summaries, logs);
    for (String input :
        List.of("hello again", "another subject", "please resume the earlier task")) {
      var current = owner(input);
      turns.start(current, input, time.plusSeconds(30 + turns.read("chat").size()));
      var prompt = advisor.before(request(current, input), null).prompt();
      assertThat(prompt.getContents())
          .contains("unrelated completed question", input)
          .doesNotContain("secret-image.png", "old intervention", "cancelled tool observations");
      assertThat(prompt.getInstructions())
          .filteredOn(m -> m instanceof UserMessage && m.getText().equals(input))
          .hasSize(1);
      turns.finish(current, ConversationTurnStore.Status.COMPLETED, "new answer");
    }
    assertThat(turns.read("chat").subList(0, saved.size())).isEqualTo(saved);
    assertThat(logs.readConversation("chat"))
        .extracting(e -> e.content())
        .contains("inspect secret-image.png", "old intervention");
    assertThat(turns.promptHistory("other").runIds()).isEmpty();
  }

  @Test
  void legacyTurnsAndOldRollingSummariesCannotReintroduceCancelledWork() {
    var turns = new ConversationTurnStore(dir);
    finish(turns, null, "failed", "old failed task", ConversationTurnStore.Status.FAILED, 0);
    finish(turns, null, "cancelled", "abandoned task", ConversationTurnStore.Status.CANCELLED, 10);
    var current = owner("current");
    turns.start(current, "new question", time.plusSeconds(20));
    var summaries = new ConversationSummaryRepository(dir);
    summaries.save(
        "chat",
        new ConversationSummary("stale summary: resume abandoned task", 100, Instant.now()));
    var projected =
        new ContextHistoryAdvisor(turns, MessageWindowChatMemory.builder().build(), summaries)
            .before(request(current, "new question"), null)
            .prompt();
    var props = new ContextCompressionProperties();
    var estimator = TokenEstimator.conservative();
    var result =
        new ContextAssembler(
                props,
                estimator,
                summaries,
                new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
                (p, m, b, r) -> {
                  throw new AssertionError("No compression needed");
                },
                null,
                null)
            .assemble(projected, "chat", "current", () -> {});
    assertThat(result.getContents())
        .contains("new question")
        .doesNotContain("old failed task", "abandoned task", "stale summary");
    assertThat(summaries.read("chat").summary()).contains("stale summary");
  }

  private AgentRunContext owner(String run) {
    return new AgentRunContext(run, "chat", dir);
  }

  private ConversationLogStore logs() {
    return new ConversationLogStore(
        dir.resolve("logs"),
        Clock.fixed(time, ZoneOffset.UTC),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
  }

  private void append(String speaker, String text, int seconds) {
    new ConversationLogStore(
            dir.resolve("logs"),
            Clock.fixed(time.plusSeconds(seconds), ZoneOffset.UTC),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()))
        .append("chat", speaker, text);
  }

  private void finish(
      ConversationTurnStore turns,
      ConversationLogStore logs,
      String run,
      String request,
      ConversationTurnStore.Status status,
      int seconds) {
    var owner = owner(run);
    turns.start(owner, request, time.plusSeconds(seconds));
    if (logs != null) append("user", request, seconds + 1);
    turns.finish(owner, status, "answer for " + request);
  }

  private ChatClientRequest request(AgentRunContext owner, String text) {
    return ChatClientRequest.builder()
        .prompt(new Prompt(new UserMessage(text), OpenAiChatOptions.builder().build()))
        .context(Map.of(ChatMemory.CONVERSATION_ID, "chat", AgentRunContext.class.getName(), owner))
        .build();
  }
}
