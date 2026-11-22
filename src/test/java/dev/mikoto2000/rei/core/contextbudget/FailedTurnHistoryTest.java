package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.chatmemory.WindowedConversationMemory;
import dev.mikoto2000.rei.context.CancelledTaskHistory;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.conversation.SqliteConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.MemoryAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

@Tag("integration")
class FailedTurnHistoryTest {
  @TempDir Path root;

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void restoredConversationKeepsCompletedHistoryButArchivesFailedAttempts(
      boolean sqlite, boolean useLogs) throws Exception {
    if (sqlite) {
      try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
        migration.prepare();
      }
    }
    var turns = store(sqlite);
    var completed = owner("completed");
    var failed = owner("failed");
    var later = owner("later-completed");
    var current = owner("current");
    turns.start(completed, "earlier completed question", Instant.EPOCH);
    turns.finish(completed, ConversationTurnStore.Status.COMPLETED, "earlier completed answer");
    turns.start(failed, "archived fixture request", Instant.EPOCH.plusSeconds(2));
    turns.finish(failed, ConversationTurnStore.Status.FAILED, "partial failed answer");
    turns.start(later, "later completed question", Instant.EPOCH.plusSeconds(4));
    turns.finish(later, ConversationTurnStore.Status.COMPLETED, "later completed answer");
    turns.start(current, "current fixture request", Instant.EPOCH.plusSeconds(6));
    if (useLogs) {
      append(1, "user", "earlier completed question");
      append(1, "assistant", "earlier completed answer");
      append(3, "user", "archived fixture request");
      append(3, "assistant", "partial failed answer");
      append(5, "user", "later completed question");
      append(5, "assistant", "later completed answer");
      append(7, "user", "current fixture request");
    }
    var saved = turns.read("chat");
    var summaries = new ConversationSummaryRepository(root);
    var oldSummary = new ConversationSummary("stale summary: archived fixture request", 100, Instant.EPOCH);
    String base = "chat" + (useLogs ? "/log" : "");
    summaries.save(base, oldSummary);
    summaries.save(
        ConversationSummaryRepository.runKey("chat", "failed"),
        new ConversationSummary("failed task observations", 1, Instant.EPOCH));

    // Reopen persisted JSON or SQLite history, as when selecting a session after restart.
    var restored = store(sqlite);
    var advisor = advisor(restored, summaries, useLogs);
    var request = request(current, "current fixture request");
    var projected = advisor.before(request, null).prompt();
    assertThat(projected.getContents())
        .contains("earlier completed question", "earlier completed answer",
            "later completed question", "later completed answer", "current fixture request")
        .doesNotContain("archived fixture request", "partial failed answer", "failed task observations");
    assertThat(projected.getInstructions().getLast().getText()).isEqualTo("current fixture request");
    assertThat(projected.getInstructions())
        .extracting(m -> m.getMetadata().get(CancelledTaskHistory.GENERATION))
        .contains("failed");
    assertThat(restored.read("chat")).containsExactlyElementsOf(saved);
    assertThat(restored.promptHistory("chat").hides("failed")).isTrue();
    assertThat(restored.promptHistory("other").runIds()).isEmpty();

    var assembled = assembler(new ContextCompressionProperties(), summaries,
        (previous, messages, budget, raw) -> "compressed history")
        .assemble(projected, "chat", "current", () -> {});
    assertThat(assembled.getContents())
        .contains("earlier completed question", "earlier completed answer",
            "later completed question", "later completed answer", "current fixture request")
        .doesNotContain("stale summary", "archived fixture request", "partial failed answer");
    assertThat(summaries.read(base)).isEqualTo(oldSummary);

    // A new explicit request remains live even when its text equals an archived request.
    var retry = advisor.before(request(current, "archived fixture request"), null).prompt();
    assertThat(retry.getInstructions().stream().filter(m -> m instanceof UserMessage))
        .extracting(Message::getText)
        .containsOnlyOnce("archived fixture request");
    assertThat(retry.getInstructions().getLast().getText()).isEqualTo("archived fixture request");
    assertThat(restored.read("chat")).containsExactlyElementsOf(saved);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void priorCancellationNamespaceCannotReintroduceAnEarlierNonAdjacentFailure(boolean useLogs) {
    var turns = new ConversationTurnStore(root);
    var failed = owner("failed");
    var completed = owner("completed");
    var cancelled = owner("cancelled");
    var later = owner("later-completed");
    var current = owner("current");
    String oldCompletedQuestion = "completed fixture detail ".repeat(150);
    String oldCompletedAnswer = "confirmed fixture fact ".repeat(100);
    turns.start(failed, "archived failed fixture", Instant.EPOCH);
    turns.finish(failed, ConversationTurnStore.Status.FAILED, "partial failed fixture");
    turns.start(completed, oldCompletedQuestion, Instant.EPOCH.plusSeconds(2));
    turns.finish(completed, ConversationTurnStore.Status.COMPLETED, oldCompletedAnswer);
    turns.start(cancelled, "cancelled fixture request", Instant.EPOCH.plusSeconds(4));
    turns.finish(cancelled, ConversationTurnStore.Status.CANCELLED, "cancelled fixture answer");
    turns.start(later, "recent completed question", Instant.EPOCH.plusSeconds(6));
    turns.finish(later, ConversationTurnStore.Status.COMPLETED, "recent completed answer");
    turns.start(current, "current fixture request", Instant.EPOCH.plusSeconds(8));
    if (useLogs) {
      append(1, "user", "archived failed fixture");
      append(1, "assistant", "partial failed fixture");
      append(3, "user", oldCompletedQuestion);
      append(3, "assistant", oldCompletedAnswer);
      append(5, "user", "cancelled fixture request");
      append(5, "assistant", "cancelled fixture answer");
      append(7, "user", "recent completed question");
      append(7, "assistant", "recent completed answer");
      append(9, "user", "current fixture request");
    }
    var savedTurns = turns.read("chat");
    var summaries = new ConversationSummaryRepository(root);
    String base = "chat" + (useLogs ? "/log" : "");
    String legacyKey = base + "/after-cancel/cancelled";
    var contaminated = new ConversationSummary(
        "legacy contaminated summary: archived failed fixture", 100, Instant.EPOCH);
    summaries.save(legacyKey, contaminated);
    var advisor = advisor(new ConversationTurnStore(root), summaries, useLogs);
    var live = request(current, "current fixture request");
    var projected = advisor.before(live, null).prompt();
    assertThat(projected.getContents())
        .contains(oldCompletedQuestion, oldCompletedAnswer,
            "recent completed question", "recent completed answer")
        .doesNotContain("archived failed fixture", "cancelled fixture request");

    var properties = new ContextCompressionProperties();
    properties.setThreshold(100);
    properties.setHardLimit(10000);
    properties.setRecentTokens(40);
    properties.setSummaryTokens(100);
    var calls = new AtomicInteger();
    var assembled = assembler(properties, summaries, (previous, messages, budget, raw) -> {
      calls.incrementAndGet();
      assertThat(previous).doesNotContain("legacy contaminated", "archived failed");
      assertThat(messages).allSatisfy(m -> assertThat(m.getText())
          .doesNotContain("archived failed fixture", "cancelled fixture request"));
      return "safe compact completed context";
    }).assemble(projected, "chat", "current", () -> {});
    assertThat(calls.get()).isEqualTo(1);
    assertThat(assembled.getContents()).contains("safe compact completed context", "current fixture request")
        .doesNotContain("legacy contaminated", "archived failed fixture", "cancelled fixture request");
    String freshKey = CancelledTaskHistory.summaryKey(base, "cancelled");
    var fresh = summaries.read(freshKey);
    assertThat(fresh.summary()).isEqualTo("safe compact completed context");
    assertThat(fresh.throughSequence()).isPositive();
    assertThat(summaries.read(legacyKey)).isEqualTo(contaminated);

    // A subsequent projection must read the same fresh namespace that assembly wrote.
    var next = advisor.before(live, null).prompt();
    var nextAssembled = assembler(new ContextCompressionProperties(), summaries,
        (previous, messages, budget, raw) -> { throw new AssertionError("No further compression needed"); })
        .assemble(next, "chat", "current", () -> {});
    assertThat(nextAssembled.getContents())
        .contains("safe compact completed context", "recent completed question", "current fixture request")
        .doesNotContain("legacy contaminated", "archived failed fixture", "cancelled fixture request");
    assertThat(turns.read("chat")).containsExactlyElementsOf(savedTurns);
    assertThat(summaries.read(legacyKey)).isEqualTo(contaminated);
  }

  @ParameterizedTest
  @CsvSource({"false,FAILED", "false,CANCELLED", "true,FAILED", "true,CANCELLED"})
  void independentNotificationCannotBecomeTheOwnerOfAnArchivedRunResponse(
      boolean sqlite, ConversationTurnStore.Status status) throws Exception {
    if (sqlite) {
      try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
        migration.prepare();
      }
    }
    var turns = store(sqlite);
    var earlier = owner("earlier");
    var archived = owner("archived");
    var later = owner("later");
    var current = owner("current");
    turns.start(earlier, "earlier completed fixture question", Instant.EPOCH);
    turns.finish(earlier, ConversationTurnStore.Status.COMPLETED, "earlier completed fixture answer");
    append(1, "user", "earlier completed fixture question");
    append(1, "assistant", "earlier completed fixture answer");
    turns.start(archived, "archived fixture request", Instant.EPOCH.plusSeconds(2));
    append(3, "user", "archived fixture request");
    String notificationText = "independent reminder fixture";
    var notification = logsAt(4).appendNotification(
        "chat", "notice-fixture", notificationText, Instant.EPOCH.plusSeconds(4),
        Map.of("fixture", "true"));
    turns.appendAssistantNotification(notification);
    append(5, "user", "archived fixture intervention");
    append(6, "assistant", "archived fixture partial answer");
    turns.finish(archived, status, "archived fixture partial answer");
    turns.start(later, "later completed fixture question", Instant.EPOCH.plusSeconds(8));
    turns.finish(later, ConversationTurnStore.Status.COMPLETED, "later completed fixture answer");
    append(9, "user", "later completed fixture question");
    append(9, "assistant", "later completed fixture answer");
    turns.start(current, "current fixture request", Instant.EPOCH.plusSeconds(10));
    append(11, "user", "current fixture request");
    var savedTurns = turns.read("chat");
    var savedLogs = logsAt(12).readConversation("chat");

    var restored = store(sqlite);
    var policy = restored.promptHistory("chat");
    assertThat(policy).isEqualTo(ConversationTurnStore.promptHistoryFor(savedTurns));
    assertThat(policy.hides("archived")).isTrue();
    assertThat(policy.generation()).isEqualTo("archived");
    assertThat(policy.attempts()).extracting(CancelledTaskHistory.Attempt::runId)
        .doesNotContain("behavior:notice-fixture");
    assertThat(policy.indexedTimestampFilter().test(Instant.EPOCH.plusSeconds(5))).isTrue();
    assertThat(policy.indexedTimestampFilter().test(Instant.EPOCH.plusSeconds(6))).isTrue();
    assertThat(policy.allowsState("behavior:notice-fixture")).isFalse();
    assertThat(policy.allowsState("later")).isTrue();
    var summaries = new ConversationSummaryRepository(root);
    var live = request(current, "current fixture request");
    for (boolean useLogs : new boolean[] {false, true}) {
      var projected = advisor(restored, summaries, useLogs).before(live, null).prompt();
      assertThat(projected.getContents())
          .contains("earlier completed fixture question", "earlier completed fixture answer",
              "later completed fixture question", "later completed fixture answer", notificationText)
          .doesNotContain("archived fixture request", "archived fixture intervention",
              "archived fixture partial answer");
      assertThat(projected.getInstructions()).filteredOn(m -> notificationText.equals(m.getText()))
          .singleElement().satisfies(m -> assertThat(m.getMetadata())
              .containsEntry("source", ConversationLogStore.BEHAVIOR_NOTIFICATION)
              .containsEntry("sourceId", "notice-fixture"));
      assertThat(projected.getInstructions()).filteredOn(m -> m instanceof UserMessage
          && "current fixture request".equals(m.getText())).hasSize(1);
      assertThat(projected.getInstructions().getLast().getText()).isEqualTo("current fixture request");
    }
    assertThat(restored.read("chat")).containsExactlyElementsOf(savedTurns);
    assertThat(logsAt(12).readConversation("chat")).containsExactlyElementsOf(savedLogs);
    assertThat(savedLogs).extracting(e -> e.content())
        .contains(notificationText, "archived fixture intervention", "archived fixture partial answer");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void notificationMatchingFailedAnswerDoesNotImportItsArchivedObservations(boolean sqlite)
      throws Exception {
    if (sqlite) {
      try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
        migration.prepare();
      }
    }
    var turns = store(sqlite);
    var failed = owner("failed");
    var current = owner("current");
    String sharedBody = "independent notification fixture body";
    turns.start(failed, "archived parent fixture request", Instant.EPOCH);
    append(1, "user", "archived parent fixture request");
    var notification = logsAt(2).appendNotification(
        "chat", "matching-fixture", sharedBody, Instant.EPOCH.plusSeconds(2),
        Map.of("fixture", "true"));
    turns.appendAssistantNotification(notification);
    append(3, "assistant", sharedBody);
    turns.finish(failed, ConversationTurnStore.Status.FAILED, sharedBody);
    turns.start(current, "current fixture request", Instant.EPOCH.plusSeconds(4));
    append(5, "user", "current fixture request");
    var savedTurns = turns.read("chat");
    var savedLogs = logsAt(6).readConversation("chat");
    assertThat(savedLogs).filteredOn(e -> sharedBody.equals(e.content())).hasSize(2);
    var summaries = new ConversationSummaryRepository(root);
    String summaryKey = ConversationSummaryRepository.runKey("chat", "failed");
    var archivedSummary = new ConversationSummary(
        "archived hidden observations", 1, Instant.EPOCH);
    summaries.save(summaryKey, archivedSummary);

    var restored = store(sqlite);
    for (boolean useLogs : new boolean[] {false, true}) {
      var projected = advisor(restored, summaries, useLogs)
          .before(request(current, "current fixture request"), null).prompt();
      assertThat(projected.getContents())
          .contains(sharedBody, "current fixture request")
          .doesNotContain("archived parent fixture request", "archived hidden observations");
      assertThat(projected.getInstructions()).filteredOn(m -> sharedBody.equals(m.getText()))
          .singleElement().satisfies(m -> assertThat(m.getMetadata())
              .containsEntry("source", ConversationLogStore.BEHAVIOR_NOTIFICATION)
              .containsEntry("sourceId", "matching-fixture"));
      assertThat(projected.getInstructions()).filteredOn(m -> m instanceof UserMessage
          && "current fixture request".equals(m.getText())).hasSize(1);
    }
    assertThat(restored.read("chat")).containsExactlyElementsOf(savedTurns);
    assertThat(logsAt(6).readConversation("chat")).containsExactlyElementsOf(savedLogs);
    assertThat(summaries.read(summaryKey)).isEqualTo(archivedSummary);
  }

  private ConversationTurnStore store(boolean sqlite) {
    return sqlite ? new SqliteConversationTurnStore(root) : new ConversationTurnStore(root);
  }

  private AgentRunContext owner(String run) {
    return new AgentRunContext(run, "chat", root);
  }

  private ChatClientRequest request(AgentRunContext current, String input) {
    return ChatClientRequest.builder()
        .prompt(new Prompt(new UserMessage(input), OpenAiChatOptions.builder().build()))
        .context(Map.of(MemoryAdvisor.CONVERSATION_ID, "chat", AgentRunContext.class.getName(), current))
        .build();
  }

  private ContextHistoryAdvisor advisor(
      ConversationTurnStore turns, ConversationSummaryRepository summaries, boolean useLogs) {
    return new ContextHistoryAdvisor(turns, WindowedConversationMemory.builder().build(),
        summaries, useLogs ? logsAt(9) : null);
  }

  private ContextAssembler assembler(
      ContextCompressionProperties properties, ConversationSummaryRepository summaries,
      ConversationCompressor compressor) {
    var tokens = TokenEstimator.conservative();
    return new ContextAssembler(properties, tokens, summaries,
        new ToolResultCompressor(new RawToolResultStore(root), tokens, 100, 70),
        compressor, null, null);
  }

  private void append(long seconds, String speaker, String text) {
    logsAt(seconds).append("chat", speaker, text);
  }

  private ConversationLogStore logsAt(long seconds) {
    return new ConversationLogStore(root.resolve("logs"),
        Clock.fixed(Instant.EPOCH.plusSeconds(seconds), ZoneOffset.UTC),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
  }
}
