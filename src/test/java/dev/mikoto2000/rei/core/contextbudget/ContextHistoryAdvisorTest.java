package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.chatmemory.WindowedConversationMemory;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 文脈履歴対話の補助の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ContextHistoryAdvisorTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"COMPLETED,true", "COMPLETED,false", "FAILED,true", "CANCELLED,true", "RUNNING,true"})
  void committedProtocolRowsUseTheirCompletedTurnAndEndpointView(
      ConversationTurnStore.Status status, boolean compatible) {
    var old = new AgentRunContext("old", "chat", dir, "project");
    var current = new AgentRunContext("current", "chat", dir, "project");
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + dir.resolve("protocol.db"));
    var payloads = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var previousExchange = payloads.newExchange(old);
    var original = AssistantMessage.builder().parts(java.util.List.of(
        new org.springframework.ai.chat.messages.part.ReasoningPart(null, null,
            new org.springframework.ai.chat.messages.part.OpaquePayload(
                org.springframework.ai.openai.responses.OpenAiResponsesMetadata.PROVIDER,
                org.springframework.ai.openai.responses.OpenAiResponsesMetadata.ENCRYPTED_CONTENT_KIND,
                "opaque-native-history"), Map.of(
                    org.springframework.ai.openai.responses.OpenAiResponsesMetadata.ITEM_ID_ATTRIBUTE, "rs_old")),
        org.springframework.ai.chat.messages.part.TextPart.of("old actual answer")))
        .properties(Map.of("rei.llm.replay-origin-owner", previousExchange.ownerKey(),
            "rei.llm.replay-origin-endpoint", "endpoint-a")).build();
    var userRef = previousExchange.withKey("input").reference(new UserMessage("old actual input"));
    var assistantRef = previousExchange.withKey("answer").reference(original);
    var pointers = payloads.historyReferences(previousExchange.scope(),
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History(1, old.runId(),
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin(previousExchange.ownerKey(), "endpoint-a"),
            java.util.List.of(new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry("input", userRef),
                new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.History.Entry("answer", assistantRef))), "turn:old");
    var logs = new dev.mikoto2000.rei.conversation.ConversationLogStore(dir.resolve("logs"),
        java.time.Clock.systemUTC(), new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    var turns = ConversationTurnStore.inMemory();
    turns.start(old, "old display request", java.time.Instant.EPOCH);
    logs.append("chat", "user", "old display request", Map.of("rei.modelMessage.run", old.runId()));
    var manifest = new java.util.HashMap<>(logs.appendProtocolHistory("chat", old.runId(), pointers));
    manifest.put("rei.modelMessage.endpoint", "endpoint-a");
    turns.recordMetadata(old, manifest);
    if (status != ConversationTurnStore.Status.RUNNING) turns.finish(old, status, "old display answer");
    logs.append("chat", "assistant", "old display answer", Map.of("rei.modelMessage.run", old.runId()));
    var currentExchange = payloads.newExchange(current);
    var origin = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin(
        currentExchange.ownerKey(), compatible ? "endpoint-a" : "endpoint-b");
    var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolContext(Map.of(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, currentExchange,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY, origin)).build();
    var loads = new java.util.concurrent.atomic.AtomicInteger();
    var advisor = new ContextHistoryAdvisor(turns, WindowedConversationMemory.builder().build(),
        new ConversationSummaryRepository(dir), logs, () -> { loads.incrementAndGet(); return payloads; });
    var request = ChatClientRequest.builder().prompt(new Prompt(new UserMessage("current request"), options))
        .context(Map.of(org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID, "chat",
            AgentRunContext.class.getName(), current)).build();
    var projected = advisor.before(request, null).prompt();
    if (status != ConversationTurnStore.Status.COMPLETED) {
      assertThat(projected.getContents()).doesNotContain("old actual", "old display", "opaque-native-history");
      assertThat(loads).hasValue(0);
    } else if (!compatible) {
      assertThat(projected.getContents()).contains("old display request", "old display answer", "current request")
          .doesNotContain("old actual", "opaque-native-history");
      assertThat(loads).hasValue(0);
      assertThat(projected.getInstructions().stream().filter(AssistantMessage.class::isInstance)
          .map(AssistantMessage.class::cast).flatMap(message -> message.getParts().stream()))
          .noneMatch(org.springframework.ai.chat.messages.part.ReasoningPart.class::isInstance);
    } else {
      assertThat(projected.getInstructions()).extracting(Message::getText)
          .containsExactly("old actual input", "old actual answer", "current request");
      var restored = (AssistantMessage) projected.getInstructions().get(1);
      assertThat(restored.getParts()).containsExactlyElementsOf(original.getParts());
      var granted = (org.springframework.ai.model.tool.ToolCallingChatOptions) projected.getOptions();
      var grant = (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HistoryReplayGrant)
          granted.getToolContext().get(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_GRANT_CONTEXT_KEY);
      assertThat(grant).isNotNull();
      assertThat(grant.permits(restored, currentExchange.ownerKey(), origin.endpoint())).isTrue();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "false,true",
    "true,true",
    "false,false",
    "true,false"
  })
  void coveredHistoryAnchorsDoNotRequireReleasedPayloads(boolean useLogs, boolean covered) {
    var owner = new AgentRunContext("current-run", "chat", dir, "project");
    var old = new AgentRunContext("old-run", "chat", dir, "project");
    var turns = ConversationTurnStore.inMemory();
    turns.start(old, "old request", java.time.Instant.EPOCH);
    var pointer =
        Map.of(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_ID, "released-payload",
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_HASH, "0".repeat(64),
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.HISTORY_OWNER, "turn:old-run");
    turns.recordMetadata(old, pointer);
    turns.finish(old, ConversationTurnStore.Status.COMPLETED, "old display answer");
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            dir.resolve("logs"),
            java.time.Clock.systemUTC(),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()));
    if (useLogs) logs.append("chat", "assistant", "old display answer", pointer);
    var summaries = new ConversationSummaryRepository(dir);
    if (covered)
      summaries.save(
          useLogs ? "chat/log" : "chat",
          new ConversationSummary(
              "retained complete summary", useLogs ? 4 : 3, java.time.Instant.now()));
    var advisor =
        new ContextHistoryAdvisor(
            turns,
            WindowedConversationMemory.builder().build(),
            summaries,
            useLogs ? logs : null,
            () -> {
              throw new IllegalStateException("uncovered payload is required");
            });
    var request =
        ChatClientRequest.builder()
            .prompt(
                new Prompt(
                    java.util.List.of(new UserMessage("current request")),
                    org.springframework.ai.openai.OpenAiChatOptions.builder().build()))
            .context(
                Map.of(
                    org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID,
                    "chat",
                    AgentRunContext.class.getName(),
                    owner))
            .build();
    if (!covered) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> advisor.before(request, null))
          .hasMessage("uncovered payload is required");
      return;
    }
    var historical = advisor.before(request, null).prompt();
    var properties = new ContextCompressionProperties();
    var estimator = TokenEstimator.conservative();
    var assembler =
        new ContextAssembler(
            properties,
            estimator,
            summaries,
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
            (previous, messages, budget, current) -> {
              throw new AssertionError("already summarized");
            },
            null,
            null);
    assertThat(assembler.assemble(historical, "chat", "current-run", () -> {}).getContents())
        .contains("retained complete summary", "current request")
        .doesNotContain("old display answer");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4})
  void durableLogAndTurnPointersTakePriorityWithoutDuplicatingTheirDisplayText(int logMode)
      throws Exception {
    var owner = new AgentRunContext("current-run", "chat", dir, "fixture-project");
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + dir.resolve("model-messages.db"));
    var payloads = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source);
    var scope = dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner);
    var message =
        AssistantMessage.builder()
            .parts(
                java.util.List.of(
                    new org.springframework.ai.chat.messages.part.ReasoningPart(
                        null,
                        null,
                        new org.springframework.ai.chat.messages.part.OpaquePayload(
                            "fixture", "encrypted_content", "opaque fixture"),
                        Map.of("itemId", "rs_one")),
                    new org.springframework.ai.chat.messages.part.TextPart(
                        "complete fixture", null, Map.of("phase", "final_answer"))))
            .properties(Map.of("rei.llm.replay-origin-owner", "fixture-owner"))
            .build();
    var ref = payloads.save(scope, "old-run", "one", message);
    var metadata = payloads.historyReference(scope, ref, "turn:old-run");
    try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(dir)) {
      migration.prepare();
    }
    var writingTurns = new dev.mikoto2000.rei.conversation.SqliteConversationTurnStore(dir);
    var oldOwner = new AgentRunContext("old-run", "chat", dir, "fixture-project");
    writingTurns.start(oldOwner, "old request", java.time.Instant.EPOCH);
    writingTurns.recordMetadata(oldOwner, metadata);
    writingTurns.finish(oldOwner, ConversationTurnStore.Status.COMPLETED, "display fixture");
    if (logMode == 3) {
      var secondOwner = new AgentRunContext("second-run", "chat", dir, "fixture-project");
      var secondRef =
          payloads.save(scope, "second-run", "one", new AssistantMessage("second payload"));
      var secondMetadata = payloads.historyReference(scope, secondRef, "turn:second-run");
      writingTurns.start(secondOwner, "second request", java.time.Instant.EPOCH);
      writingTurns.recordMetadata(secondOwner, secondMetadata);
      writingTurns.finish(secondOwner, ConversationTurnStore.Status.COMPLETED, "display fixture");
    }
    var mapper =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC);
    if (logMode == 1)
      new dev.mikoto2000.rei.conversation.ConversationLogStore(dir.resolve("logs"), clock, mapper)
          .append("chat", "assistant", "display fixture", metadata);
    if (logMode == 2 || logMode == 3)
      new dev.mikoto2000.rei.conversation.ConversationLogStore(dir.resolve("logs"), clock, mapper)
          .append("chat", "assistant", "display fixture");
    if (logMode == 4)
      new dev.mikoto2000.rei.conversation.ConversationLogStore(dir.resolve("logs"), clock, mapper)
          .appendNotification(
              "chat", "fixture-notification", "display fixture", java.time.Instant.EPOCH, Map.of());
    var turns = new dev.mikoto2000.rei.conversation.SqliteConversationTurnStore(dir);
    var logs =
        new dev.mikoto2000.rei.conversation.ConversationLogStore(
            dir.resolve("logs"), clock, mapper);
    var advisor =
        new ContextHistoryAdvisor(
            turns,
            WindowedConversationMemory.builder().build(),
            new ConversationSummaryRepository(dir),
            logs,
            () -> new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(source));
    var request =
        ChatClientRequest.builder()
            .prompt(new Prompt(new UserMessage("current request")))
            .context(
                Map.of(
                    org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID,
                    "chat",
                    AgentRunContext.class.getName(),
                    owner))
            .build();
    if (logMode == 3) {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> advisor.before(request, null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Model message persistence rejected");
      return;
    }
    var messages = advisor.before(request, null).prompt().getInstructions();
    if (logMode == 4) {
      assertThat(messages.stream().map(Message::getText))
          .contains("display fixture")
          .doesNotContain("complete fixture");
      assertThat(
              messages.stream()
                  .filter(m -> m instanceof AssistantMessage)
                  .flatMap(m -> ((AssistantMessage) m).getParts().stream()))
          .noneMatch(p -> p instanceof org.springframework.ai.chat.messages.part.ReasoningPart);
      return;
    }
    assertThat(messages.stream().map(Message::getText))
        .contains("complete fixture")
        .doesNotContain("display fixture");
    var restored =
        messages.stream()
            .filter(m -> m instanceof AssistantMessage && "complete fixture".equals(m.getText()))
            .toList();
    assertThat(restored).hasSize(1);
    assertThat(((AssistantMessage) restored.getFirst()).getParts())
        .containsExactlyElementsOf(message.getParts());
    payloads.release(
        scope,
        ref,
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ReferenceKind.HISTORY,
        "turn:old-run");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> advisor.before(request, null))
        .hasMessage("Model message persistence rejected");
  }

  @Test
  void assigningHistorySequencePreservesOrderedAssistantPartsAndTypedToolResults() {
    var reasoning =
        new org.springframework.ai.chat.messages.part.ReasoningPart(
            null,
            "fixture summary",
            new org.springframework.ai.chat.messages.part.OpaquePayload(
                "fixture", "encrypted_content", "opaque fixture"),
            Map.of("itemId", "rs_one"));
    var input =
        AssistantMessage.builder()
            .parts(
                java.util.List.of(
                    reasoning,
                    new org.springframework.ai.chat.messages.part.TextPart(
                        "fixture commentary", null, Map.of("phase", "commentary")),
                    new org.springframework.ai.chat.messages.part.ToolCallPart(
                        new AssistantMessage.ToolCall("call_one", "function", "fixture", "{}"),
                        null,
                        Map.of("itemId", "fc_one"))))
            .properties(Map.of("rei.llm.replay-origin-owner", "fixture-owner"))
            .build();
    var historical = (AssistantMessage) ContextHistoryAdvisor.historical(input, 7);
    assertThat(historical.getParts()).containsExactlyElementsOf(input.getParts());
    assertThat(historical.getMetadata())
        .containsEntry(ContextHistoryAdvisor.SEQUENCE, 7L)
        .containsEntry("rei.llm.replay-origin-owner", "fixture-owner");
    var tool =
        org.springframework.ai.chat.messages.ToolResponseMessage.builder()
            .responses(
                java.util.List.of(
                    new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                        "call_one", "fixture", "fixture result")))
            .metadata(Map.of("rei.reviewBudgetRecovery", true))
            .build();
    var result = ContextHistoryAdvisor.historical(tool, 8);
    assertThat(result).isInstanceOf(org.springframework.ai.chat.messages.ToolResponseMessage.class);
    assertThat(((org.springframework.ai.chat.messages.ToolResponseMessage) result).getResponses())
        .isEqualTo(tool.getResponses());
    assertThat(result.getMetadata())
        .containsEntry("rei.reviewBudgetRecovery", true)
        .containsEntry(ContextHistoryAdvisor.SEQUENCE, 8L);
  }

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
            turns, WindowedConversationMemory.builder().build(), summaries, logs);
    var result =
        advisor.before(
            ChatClientRequest.builder()
                .prompt(
                    new Prompt(
                        java.util.List.of(new UserMessage("current")),
                        org.springframework.ai.openai.OpenAiChatOptions.builder().build()))
                .context(
                    Map.of(
                        org.springframework.ai.chat.client.advisor.api.MemoryAdvisor
                            .CONVERSATION_ID,
                        "chat"))
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
            WindowedConversationMemory.builder().build(),
            new ConversationSummaryRepository(dir));
    advisor.before(
        ChatClientRequest.builder()
            .prompt(new Prompt(new UserMessage("current")))
            .context(
                Map.of(
                    org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID,
                    "chat"))
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
            turns, WindowedConversationMemory.builder().build(), summaries, logs);
    var result =
        advisor
            .before(
                ChatClientRequest.builder()
                    .prompt(new Prompt(new UserMessage("current")))
                    .context(
                        Map.of(
                            org.springframework.ai.chat.client.advisor.api.MemoryAdvisor
                                .CONVERSATION_ID,
                            "chat"))
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
    var memory = WindowedConversationMemory.builder().maxMessages(2).build();
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
                    .context(
                        Map.of(
                            org.springframework.ai.chat.client.advisor.api.MemoryAdvisor
                                .CONVERSATION_ID,
                            "chat"))
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
    var memory = WindowedConversationMemory.builder().maxMessages(2).build();
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
                    org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID,
                    "chat",
                    AgentRunContext.class.getName(),
                    current))
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
