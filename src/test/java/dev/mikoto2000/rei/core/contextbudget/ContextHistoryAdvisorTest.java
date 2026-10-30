package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 文脈履歴対話の補助の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ContextHistoryAdvisorTest {
  @Test
  void coveredMessagesKeepOneAnchorAndUncoveredRunObservations() {
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            dir.resolve("covered-logs"),
            java.time.Clock.systemUTC(),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    logs.append("chat", "user", "old request");
    logs.append("chat", "assistant", "old answer");
    logs.append("chat", "user", "new request");
    var time = logs.readConversation("chat").get(1).timestamp().toInstant();
    var turns =
        new ConversationTurnStore((Path) null) {
          @Override
          public synchronized java.util.List<Turn> read(String conversation) {
            return java.util.List.of(
                new Turn("old", "old request", Status.COMPLETED, "old answer", time));
          }
        };
    var summaries = new ConversationSummaryRepository(dir);
    summaries.save("chat/log", new ConversationSummary("earlier conversation", 7, time));
    summaries.save(
        ConversationSummaryRepository.runKey("chat", "old"),
        new ConversationSummary("observations", 1, time));
    var advisor =
        new ContextHistoryAdvisor(
            turns, MessageWindowChatMemory.builder().build(), summaries, logs);
    var result =
        advisor.before(
            ChatClientRequest.builder()
                .prompt(new Prompt(new UserMessage("current")))
                .context(Map.of(ChatMemory.CONVERSATION_ID, "chat"))
                .build(),
            null);
    var text =
        result.prompt().getInstructions().stream()
            .map(org.springframework.ai.chat.messages.Message::getText)
            .toList();
    assertThat(text)
        .contains(
            "old request",
            "Prior run observations (historical data):\nobservations",
            "new request",
            "current");
    assertThat(text).doesNotContain("old answer");
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** モデルへ渡す文脈の管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void constructsCancellationPolicyFromTheAlreadyReadHistory() {
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    var turns =
        new ConversationTurnStore((Path) null) {
          @Override
          public synchronized java.util.List<Turn> read(String conversation) {
            reads.incrementAndGet();
            return java.util.List.of();
          }
        };
    var advisor =
        new ContextHistoryAdvisor(
            turns,
            MessageWindowChatMemory.builder().build(),
            new ConversationSummaryRepository(dir));
    advisor.before(
        ChatClientRequest.builder()
            .prompt(new Prompt(new UserMessage("current")))
            .context(Map.of(ChatMemory.CONVERSATION_ID, "chat"))
            .build(),
        null);
    assertThat(reads).hasValue(1);
  }

  @Test
  void summaryLookupKeepsTheFirstTurnOnTimestampTiesAndExcludesFutureTurns() {
    var start = java.time.Instant.EPOCH;
    var rows =
        java.util.List.of(
            new ConversationTurnStore.Turn(
                "first", "request", ConversationTurnStore.Status.COMPLETED, "answer", start),
            new ConversationTurnStore.Turn(
                "second", "request", ConversationTurnStore.Status.COMPLETED, "other", start),
            new ConversationTurnStore.Turn(
                "future",
                "request",
                ConversationTurnStore.Status.COMPLETED,
                "answer",
                start.plusSeconds(10)));
    var turns =
        new ConversationTurnStore((Path) null) {
          @Override
          public synchronized java.util.List<Turn> read(String conversation) {
            return rows;
          }
        };
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            dir.resolve("logs"),
            java.time.Clock.fixed(start.plusSeconds(1), java.time.ZoneOffset.UTC),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    logs.append("chat", "assistant", "answer");
    var summaries = new ConversationSummaryRepository(dir);
    for (var row : rows)
      summaries.save(
          ConversationSummaryRepository.runKey("chat", row.runId()),
          new ConversationSummary(row.runId() + " observations", 1, start));
    var advisor =
        new ContextHistoryAdvisor(
            turns, MessageWindowChatMemory.builder().build(), summaries, logs);
    var result =
        advisor
            .before(
                ChatClientRequest.builder()
                    .prompt(new Prompt(new UserMessage("current")))
                    .context(Map.of(ChatMemory.CONVERSATION_ID, "chat"))
                    .build(),
                null)
            .prompt();
    assertThat(result.getContents())
        .contains("first observations")
        .doesNotContain("second observations", "future observations");
  }

  @Test
  void includesInterventionsAndAuxiliaryResultsFromPersistentLog() {
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            dir.resolve("logs"),
            java.time.Clock.systemUTC(),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    logs.append("chat", "user", "original request");
    logs.append("chat", "user", "intervention: keep API unchanged");
    logs.append("chat", "assistant", "auxiliary URL summary");
    logs.append("chat", "user", "current request");
    var memory = MessageWindowChatMemory.builder().maxMessages(2).build();
    var advisor =
        new ContextHistoryAdvisor(
            new ConversationTurnStore(dir), memory, new ConversationSummaryRepository(dir), logs);
    var prompt =
        new Prompt(
            new UserMessage("current request"),
            org.springframework.ai.openai.OpenAiChatOptions.builder().build());
    var result =
        advisor
            .before(
                ChatClientRequest.builder()
                    .prompt(prompt)
                    .context(Map.of(ChatMemory.CONVERSATION_ID, "chat"))
                    .build(),
                null)
            .prompt();
    assertThat(result.getInstructions())
        .extracting(Message::getText)
        .containsExactly(
            "original request",
            "intervention: keep API unchanged",
            "auxiliary URL summary",
            "current request");
    assertThat(
            ((org.springframework.ai.model.tool.ToolCallingChatOptions) result.getOptions())
                .getToolContext())
        .containsEntry("rei.historySource", "log");
  }

  /** モデルへ渡す文脈の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void initiallyEmptyWorkingSetStillHasDedicatedRefreshSlot() {
    var working = new dev.mikoto2000.rei.core.working.WorkingSet(20, java.time.Clock.systemUTC());
    var advisor = new dev.mikoto2000.rei.core.working.WorkingSetAdvisor(working);
    var request =
        ChatClientRequest.builder()
            .prompt(new Prompt(new UserMessage("raw user request")))
            .context(Map.of("rei.contextProjection", true))
            .build();
    var result = advisor.before(request, null).prompt();
    assertThat(result.getUserMessage().getText()).isEqualTo("raw user request");
    assertThat(result.getInstructions())
        .anyMatch(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.workingSet")));
  }

  /** 圧縮の後も対象の情報が残ることを確認する。 */
  @Test
  void historyBeyondMemoryWindowSurvivesCompressionAndRestart() {
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
    var request =
        ChatClientRequest.builder()
            .prompt(new Prompt(new UserMessage("next request")))
            .context(
                Map.of(
                    ChatMemory.CONVERSATION_ID, "chat", AgentRunContext.class.getName(), current))
            .build();
    var assembled = advisor.before(request, null).prompt();
    assertThat(assembled.getContents()).contains("requirement 0", "requirement 4", "next request");
    var props = new ContextCompressionProperties();
    props.setThreshold(300);
    props.setHardLimit(600);
    props.setRecentTokens(80);
    props.setSummaryTokens(100);
    var tokens = TokenEstimator.conservative();
    new ContextAssembler(
            props,
            tokens,
            summaries,
            new ToolResultCompressor(new RawToolResultStore(dir), tokens, 100, 70),
            (p, m, b, r) -> "decisions retained",
            null,
            null)
        .assemble(assembled, "chat", "current", () -> {});
    assertThat(new ConversationTurnStore(dir).read("chat")).containsExactlyElementsOf(before);
    assertThat(memory.get("chat")).hasSizeLessThanOrEqualTo(2);
    assertThat(new ConversationSummaryRepository(dir).read("chat").throughSequence())
        .isGreaterThan(0);
  }
}
