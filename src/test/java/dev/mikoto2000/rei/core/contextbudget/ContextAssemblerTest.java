package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 文脈文脈の組み立ての結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ContextAssemblerTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oversizedDraftRepairCommitsOnlyTheCompleteToolExchange(boolean failLater) {
    var properties = config();
    properties.setSummaryInputTokens(1024);
    properties.setSummaryTokens(12000);
    properties.setHardLimit(800000);
    var saved = new ConversationSummaryRepository(dir);
    saved.save(
        "conversation", new ConversationSummary("prior decision", 1, java.time.Instant.now()));
    var targets = new ArrayList<Integer>();
    ConversationCompressor compressor =
        new ConversationCompressor() {
          public String summarize(
              String previous, List<Message> messages, int target, Prompt owner) {
            throw new AssertionError("Paged calls must use their shared deadline");
          }

          @Override
          public Summary summarizeResult(
              String previous,
              List<Message> messages,
              int target,
              Prompt owner,
              dev.mikoto2000.rei.llm.ModelCallBudget budget,
              java.time.Duration timeout) {
            assertThat(saved.read("conversation").throughSequence()).isEqualTo(1);
            targets.add(target);
            if (targets.size() == 1 || failLater && targets.size() >= 3)
              return new Summary("oversized draft", 4000);
            return new Summary("prior decision; pending tests; reference retained", 20);
          }
        };
    var call =
        AssistantMessage.builder()
            .content("")
            .properties(Map.of(ContextHistoryAdvisor.SEQUENCE, 2L))
            .toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "readFile", "{}")))
            .build();
    var response =
        ToolResponseMessage.builder()
            .metadata(Map.of(ContextHistoryAdvisor.SEQUENCE, 3L))
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        "call", "readFile", "untrusted ".repeat(1200))))
            .build();
    var raw =
        new Prompt(
            List.of(
                new SystemMessage("policy"),
                call,
                response,
                ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 4),
                new UserMessage("current")));
    new ContextAssembler(
            properties,
            estimator,
            saved,
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
            compressor,
            null,
            null)
        .assemble(raw, "conversation", "run", () -> {});
    assertThat(targets).startsWith(256, 128).hasSizeGreaterThan(2);
    assertThat(saved.read("conversation").throughSequence()).isEqualTo(failLater ? 1 : 3);
    assertThat(raw.getInstructions()).hasSize(5);
    assertThat(response.getResponses().getFirst().responseData())
        .isEqualTo("untrusted ".repeat(1200));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void oversizedToolExchangeIsPagedAndCursorCommitsOnlyAfterEveryPage(boolean failLater) {
    var properties = config();
    properties.setSummaryInputTokens(1024);
    properties.setHardLimit(800000);
    var saved = new ConversationSummaryRepository(dir);
    String previousSummary = "prior decision; ".repeat(400);
    saved.save(
        "conversation", new ConversationSummary(previousSummary, 1, java.time.Instant.now()));
    var captured = new StringBuilder();
    var seen = new AtomicInteger();
    var call =
        AssistantMessage.builder()
            .content("inspect")
            .properties(Map.of(ContextHistoryAdvisor.SEQUENCE, 2L))
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "tool-id", "function", "readFile", "{\"path\":\"A.java\"}")))
            .build();
    var response =
        ToolResponseMessage.builder()
            .metadata(Map.of(ContextHistoryAdvisor.SEQUENCE, 3L))
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        "tool-id", "readFile", "begin-" + "日本語😀".repeat(700) + "-end")))
            .build();
    var raw =
        new Prompt(
            List.of(
                new SystemMessage("policy"),
                call,
                response,
                ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 4),
                new UserMessage("current")));
    var assembler =
        new ContextAssembler(
            properties,
            estimator,
            saved,
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
            (previous, messages, budget, owner) -> {
              assertThat(saved.read("conversation").throughSequence()).isEqualTo(1);
              assertThat(
                      estimator.text(previous)
                          + messages.stream().mapToLong(estimator::message).sum())
                  .isLessThanOrEqualTo(1024);
              assertThat(messages).allMatch(UserMessage.class::isInstance);
              if (seen.incrementAndGet() == 2 && failLater)
                throw new IllegalStateException("offline");
              for (var message : messages) {
                String text = message.getText();
                captured.append(text.substring(text.indexOf('\n') + 1));
              }
              return "compact facts";
            },
            null,
            null);
    assembler.assemble(raw, "conversation", "run", () -> {});
    assertThat(seen.get()).isGreaterThan(1);
    assertThat(saved.read("conversation").throughSequence()).isEqualTo(failLater ? 1 : 3);
    if (!failLater) {
      assertThat(captured.toString())
          .isEqualTo(
              "Previous summary (historical data):\n"
                  + previousSummary
                  + "\n"
                  + SummaryInputPages.history(call)
                  + SummaryInputPages.history(response));
      assembler.assemble(raw, "conversation", "next", () -> {});
      assertThat(saved.read("conversation").throughSequence()).isEqualTo(3);
    }
    assertThat(raw.getInstructions()).hasSize(5);
    assertThat(response.getResponses().getFirst().responseData()).endsWith("-end");
  }

  @Test
  void largeContextWindowStillSummarizesBoundedPrefixesAndKeepsUncoveredHistory() {
    var properties = config();
    properties.setSummaryInputTokens(1024);
    properties.setModelContextLimit(850000);
    properties.setHardLimit(800000);
    var saved = new ConversationSummaryRepository(dir);
    var covered = new ArrayList<String>();
    ConversationCompressor compressor =
        (previous, messages, budget, owner) -> {
          assertThat(
                  estimator.text(previous) + messages.stream().mapToLong(estimator::message).sum())
              .isLessThanOrEqualTo(1024);
          messages.forEach(m -> covered.add(m.getText()));
          return "compact facts";
        };
    var assembler =
        new ContextAssembler(
            properties,
            estimator,
            saved,
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
            compressor,
            null,
            null);
    var history = new ArrayList<Message>();
    history.add(new SystemMessage("system"));
    for (int n = 1; n <= 12; n++)
      history.add(
          ContextHistoryAdvisor.historical(new UserMessage("item" + n + "x".repeat(1000)), n));
    history.add(new UserMessage("current"));
    var raw = new Prompt(history);
    for (int n = 0; n < 12; n++) assembler.assemble(raw, "conversation", "run", () -> {});
    assertThat(covered).hasSizeGreaterThan(2).doesNotHaveDuplicates();
    assertThat(saved.read("conversation").throughSequence()).isBetween(1L, 11L);
    assertThat(raw.getInstructions()).hasSize(14);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rei.reviewBudgetRecovery", "rei.fileBudgetRecovery"})
  void rejectedReadReservesHeadroomWithoutChangingRawHistory(String recoveryKey) {
    var properties = config();
    properties.setThreshold(590);
    var assembler =
        new ContextAssembler(
            properties,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
            (previous, messages, budget, current) -> {
              calls.incrementAndGet();
              return "saved decisions";
            },
            null,
            null);
    var feedback =
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        "read",
                        "readNextReviewWorkItem",
                        "{\"error\":\"CONTEXT_BUDGET_INSUFFICIENT\"}")))
            .metadata(Map.of(recoveryKey, true))
            .build();
    var raw =
        new Prompt(
            List.of(
                new SystemMessage("system"),
                ContextHistoryAdvisor.historical(new UserMessage("old ".repeat(440)), 1),
                ContextHistoryAdvisor.historical(new AssistantMessage("saved assessment"), 2),
                new UserMessage("continue the same review"),
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "read", "function", "readNextReviewWorkItem", "{}")))
                    .build(),
                feedback));
    long original = raw.getInstructions().stream().mapToLong(estimator::message).sum();
    assertThat(original).isBetween(526L, 590L);
    var projected = assembler.assemble(raw, "conversation", "recovery", () -> {});
    assertThat(calls.get()).isGreaterThan(0);
    assertThat(projected.getInstructions().stream().mapToLong(estimator::message).sum())
        .isLessThanOrEqualTo(525);
    assertThat(raw.getInstructions().stream().mapToLong(estimator::message).sum())
        .isEqualTo(original);
    assertThat(projected.getUserMessage().getText()).isEqualTo("continue the same review");
    assertThat(projected.getInstructions().getLast()).isEqualTo(feedback);
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う概算。 */
  final TokenEstimator estimator = TokenEstimator.conservative();

  /** 検証に使う呼び出し回数。 */
  final AtomicInteger calls = new AtomicInteger();

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  ContextCompressionProperties config() {
    var p = new ContextCompressionProperties();
    p.setThreshold(300);
    p.setHardLimit(600);
    p.setRecentTokens(80);
    p.setSummaryTokens(100);
    return p;
  }

  /** {@code assembler} の条件を再現するための補助情報を返す。 */
  ContextAssembler assembler(ConversationCompressor compressor) {
    return new ContextAssembler(
        config(),
        estimator,
        new ConversationSummaryRepository(dir),
        new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
        compressor,
        null,
        null);
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  Prompt prompt(String old) {
    return new Prompt(
        List.of(
            new SystemMessage("system"),
            ContextHistoryAdvisor.historical(new UserMessage(old), 1),
            ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 2),
            new UserMessage("current request and Working Set")));
  }

  /** 会話返信形式について、履歴圧縮の後も対象の情報が残ることを確認する。 */
  @Test
  void conversationReplyStyleSurvivesHistoryCompressionWithoutReplacingCharacter() {
    var request =
        org.springframework.ai.chat.client.ChatClientRequest.builder()
            .prompt(prompt("x".repeat(3000)))
            .build();
    var styled =
        new dev.mikoto2000.rei.core.chat.ConversationStyleAdvisor(
                dev.mikoto2000.rei.core.chat.ResponseStyle.CONVERSATION)
            .before(
                request,
                org.mockito.Mockito.mock(
                    org.springframework.ai.chat.client.advisor.api.AdvisorChain.class))
            .prompt();
    var projected =
        assembler(
                (previous, messages, budget, current) -> {
                  calls.incrementAndGet();
                  return "old summary";
                })
            .assemble(styled, "conversation", "style-run", () -> {});
    assertThat(calls.get()).isGreaterThan(0);
    assertThat(
            projected.getInstructions().stream()
                .filter(m -> m instanceof SystemMessage)
                .map(Message::getText)
                .collect(java.util.stream.Collectors.joining("\n")))
        .contains("system", "会話スタイル", "承認事項は省略しない");
    assertThat(projected.getUserMessage().getText()).isEqualTo("current request and Working Set");
  }

  /** モデルへ渡す文脈の管理で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void runObservationsFollowOriginalSystemPrefixAndSummaryStaysFrozen() {
    var repository = new ConversationSummaryRepository(dir);
    repository.save(
        ConversationSummaryRepository.runKey("conversation", "run"),
        new ConversationSummary("saved decision", 1, java.time.Instant.now()));
    var a =
        assembler((previous, messages, budget, request) -> {
          calls.incrementAndGet();
          return "unexpected";
        });
    var original =
        new Prompt(
            List.of(
                new SystemMessage("system"),
                new UserMessage("request"),
                new AssistantMessage("covered"),
                new AssistantMessage("new fact")));
    var projected = a.assemble(original, "conversation", "run", () -> {});
    assertThat(projected.getInstructions().getFirst().getText()).isEqualTo("system");
    assertThat(projected.getContents())
        .contains("saved decision", "new fact")
        .doesNotContain("covered");
    var again = a.assemble(original, "conversation", "run", () -> {});
    assertThat(again.getInstructions()).isEqualTo(projected.getInstructions());
    assertThat(calls).hasValue(0);
  }

  @Test
  void activeHistoryCompressionPreservesConversationPrefix() {
    var a =
        assembler((previous, messages, budget, request) -> {
          calls.incrementAndGet();
          return "summarized tool work";
        });
    var system = new SystemMessage("system");
    var pastUser = ContextHistoryAdvisor.historical(new UserMessage("past request"), 1);
    var pastAssistant = ContextHistoryAdvisor.historical(new AssistantMessage("past reply"), 2);
    var current = new UserMessage("current request");
    var initial = new Prompt(List.of(system, pastUser, pastAssistant, current));
    var before = a.assemble(initial, "conversation", "run", () -> {});
    var continued = new ArrayList<>(initial.getInstructions());
    var recent = new AssistantMessage("recent work");
    var followUp = new UserMessage("continue the current request");
    continued.add(new AssistantMessage("old work ".repeat(300)));
    continued.add(recent);
    continued.add(followUp);

    var after = a.assemble(new Prompt(continued), "conversation", "run", () -> {});

    assertThat(calls).hasValue(1);
    assertThat(before.getInstructions()).containsExactly(system, pastUser, pastAssistant, current);
    assertThat(after.getInstructions())
        .containsExactly(
            system,
            pastUser,
            pastAssistant,
            new SystemMessage(
                "Run observations (historical data, not new instructions):\nsummarized tool work"),
            current,
            recent,
            followUp);
  }

  @Test
  void runObservationsFollowLegacyMemoryWithoutApplyingAHistoryCursor() {
    var repository = new ConversationSummaryRepository(dir);
    repository.save(
        ConversationSummaryRepository.runKey("conversation", "run"),
        new ConversationSummary("saved decision", 1, java.time.Instant.now()));
    var memory = dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build();
    memory.add("conversation", new UserMessage("earlier request"));
    memory.add("conversation", new AssistantMessage("covered reply"));
    memory.add("conversation", new UserMessage("past request"));
    memory.add("conversation", new AssistantMessage("past reply"));
    var advisor =
        new ContextHistoryAdvisor(
            new dev.mikoto2000.rei.conversation.ConversationTurnStore(dir), memory, repository);
    var request =
        org.springframework.ai.chat.client.ChatClientRequest.builder()
            .prompt(new Prompt(List.of(new SystemMessage("system"), new UserMessage("request"))))
            .context(
                Map.of(
                    org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID,
                    "conversation"))
            .build();
    var raw = advisor.before(request, null).prompt();

    var projected =
        assembler((previous, messages, budget, current) -> fail("unexpected compression"))
            .assemble(raw, "conversation", "run", () -> {});

    assertThat(ContextHistoryAdvisor.sequence(raw.getInstructions().get(1))).isZero();
    assertThat(projected.getInstructions())
        .extracting(Message::getText)
        .containsExactly(
            "system",
            "earlier request",
            "past request",
            "past reply",
            "Run observations (historical data, not new instructions):\nsaved decision",
            "request");
  }

  /** モデルへ渡す文脈の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void updatedWorkingSetAndNewSystemPolicyAreUsedWithoutStaleSnapshot() {
    var current = new java.util.concurrent.atomic.AtomicReference<>("Working Set: latest revision");
    var a = assembler((previous, messages, budget, request) -> "summary");
    a.setWorkingSet(current::get);
    var marker =
        SystemMessage.builder()
            .text("Working Set: old")
            .metadata(Map.of("rei.workingSet", true))
            .build();
    var raw =
        new Prompt(List.of(new SystemMessage("policy-v1"), new UserMessage("request"), marker));
    assertThat(a.assemble(raw, "conversation", "run", () -> {}).getContents())
        .contains("latest revision")
        .doesNotContain("Working Set: old");
    current.set("Working Set: deleted old path, moved new path");
    var revised =
        new Prompt(List.of(new SystemMessage("policy-v2"), new UserMessage("request"), marker));
    var next = a.assemble(revised, "conversation", "run", () -> {});
    assertThat(next.getInstructions().getFirst().getText()).isEqualTo("policy-v2");
    assertThat(next.getInstructions().getLast().getMetadata().get("rei.workingSet"))
        .isEqualTo(true);
    assertThat(dev.mikoto2000.rei.timing.PrefixDiagnostics.sources(next))
        .extracting(dev.mikoto2000.rei.timing.PrefixDiagnostics.Source::id)
        .contains("WORKING_SET");
    assertThat(next.getContents())
        .contains("deleted old path, moved new path")
        .doesNotContain("latest revision", "policy-v1");
    assertThat(raw.getContents()).contains("Working Set: old");
  }

  @Test
  void takesOneWorkingSetSnapshotPerAssembly() {
    var contextAssembler = assembler((previous, messages, budget, request) -> "summary");
    var snapshots = new AtomicInteger();
    contextAssembler.setWorkingSet(() -> "Working Set: revision " + snapshots.incrementAndGet());
    var marker =
        SystemMessage.builder()
            .text("Working Set: old")
            .metadata(Map.of("rei.workingSet", true))
            .build();
    var raw = new Prompt(List.of(new UserMessage("request"), marker));

    var first = contextAssembler.assemble(raw, "conversation", "run", () -> {});
    assertThat(first.getInstructions().getLast().getText()).isEqualTo("Working Set: revision 1");
    assertThat(snapshots.get()).isEqualTo(1);

    var next = contextAssembler.assemble(raw, "conversation", "run", () -> {});
    assertThat(next.getInstructions().getLast().getText()).isEqualTo("Working Set: revision 2");
    assertThat(snapshots.get()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void workingSetChangesOnlyTheTailAfterCompleteToolExchanges(boolean compressionEnabled) {
    var properties = config();
    properties.setEnabled(compressionEnabled);
    var a =
        new ContextAssembler(
            properties,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
            (p, m, b, r) -> {
              throw new AssertionError("Short history must not be summarized");
            },
            null,
            null);
    var current = new java.util.concurrent.atomic.AtomicReference<>("Working Set: a.java");
    a.setWorkingSet(current::get);
    var policy = new SystemMessage("policy");
    var request = new UserMessage("request");
    var marker =
        SystemMessage.builder()
            .text("obsolete snapshot")
            .metadata(Map.of("rei.workingSet", true))
            .build();
    var call =
        AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "readFile", "{}")))
            .build();
    var result =
        ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse("id", "readFile", "contents")))
            .build();
    var raw = new Prompt(List.of(policy, request, marker, call, result));
    var first = a.assemble(raw, "conversation", "run", () -> {});
    current.set("Working Set: b.java");
    var next = a.assemble(raw, "conversation", "run", () -> {});
    assertThat(first.getInstructions().subList(0, 4))
        .containsExactly(policy, request, call, result);
    assertThat(next.getInstructions().subList(0, 4))
        .containsExactlyElementsOf(first.getInstructions().subList(0, 4));
    assertThat(next.getInstructions()).hasSize(5);
    assertThat(next.getInstructions().getLast().getText()).isEqualTo("Working Set: b.java");
    assertThat(next.getContents()).doesNotContain("a.java", "obsolete snapshot");
    current.set("");
    var cleared = a.assemble(next, "conversation", "run", () -> {});
    assertThat(cleared.getInstructions()).hasSize(5);
    assertThat(cleared.getInstructions().getLast().getText()).contains("working set is empty");
    assertThat(cleared.getContents()).doesNotContain("b.java");
    assertThat(raw.getInstructions()).containsExactly(policy, request, marker, call, result);
  }

  /** 未満判定の境界値について、元のメッセージ一覧を維持することを確認する。 */
  @Test
  void belowThresholdKeepsOriginalMessages() {
    var p = prompt("short");
    var result =
        assembler(
                (previous, messages, budget, request) -> {
                  calls.incrementAndGet();
                  return "summary";
                })
            .assemble(p, "conversation", "run", () -> {});
    assertThat(result.getInstructions()).containsExactlyElementsOf(p.getInstructions());
    assertThat(calls).hasValue(0);
  }

  /** モデルへ渡す文脈の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void reservesTheConfiguredOutputCapForEitherOpenAiTokenParameter(boolean completionTokens) {
    var properties = config();
    properties.setModelContextLimit(1000);
    properties.setCompletionReserve(100);
    properties.setToolReserve(0);
    properties.setSafetyMargin(0);
    var a =
        new ContextAssembler(
            properties,
            estimator,
            new ConversationSummaryRepository(dir),
            new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70),
            (previous, messages, budget, request) -> {
              calls.incrementAndGet();
              return "summary";
            },
            null,
            null);
    var builder =
        org.springframework.ai.openai.OpenAiChatOptions.builder().model("configured-model");
    if (completionTokens) builder.maxCompletionTokens(800);
    else builder.maxTokens(800);
    var options = builder.build();
    // One user message has eight tokens of overhead: 768 ASCII characters are 192 tokens.
    var atLimit = new Prompt(List.of(new UserMessage("x".repeat(768))), options);
    assertThat(estimator.message(atLimit.getUserMessage())).isEqualTo(200);

    var assembled = a.assemble(atLimit, "conversation", "run", () -> {});
    assertThat(assembled.getInstructions()).containsExactlyElementsOf(atLimit.getInstructions());
    assertThat(assembled.getOptions()).isSameAs(options);
    var overLimit = new Prompt(List.of(new UserMessage("x".repeat(772))), options);
    assertThatThrownBy(() -> a.assemble(overLimit, "conversation", "next", () -> {}))
        .hasMessageContaining("CONTEXT_HARD_LIMIT");
    assertThat(calls).hasValue(0);
    assertThat(options.getMaxCompletionTokens()).isEqualTo(completionTokens ? 800 : null);
    assertThat(options.getMaxTokens()).isEqualTo(completionTokens ? null : 800);
  }

  /** モデルへ渡す文脈の管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void compressesOnlyOldHistoryAndPersistsCursorAcrossRestart() {
    var p = prompt("old ".repeat(500));
    var result =
        assembler(
                (previous, messages, budget, request) -> {
                  calls.incrementAndGet();
                  return "decided API X";
                })
            .assemble(p, "conversation", "run", () -> {});
    assertThat(result.getContents())
        .contains("decided API X", "recent", "current request and Working Set")
        .doesNotContain("old old old");
    assertThat(p.getContents()).contains("old old old");
    assembler(
            (previous, messages, budget, request) -> {
              calls.incrementAndGet();
              return "unexpected";
            })
        .assemble(p, "conversation", "next", () -> {});
    assertThat(calls).hasValue(1);
    assertThat(new ConversationSummaryRepository(dir).read("conversation").throughSequence())
        .isEqualTo(1);
  }

  /** モデルへ渡す文脈の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureAndInsufficientGainFallBackWithoutRetry() {
    for (boolean fail : List.of(true, false)) {
      calls.set(0);
      var result =
          assembler(
                  (previous, messages, budget, request) -> {
                    calls.incrementAndGet();
                    if (fail) throw new IllegalStateException("offline");
                    return "bad ".repeat(600);
                  })
              .assemble(prompt("old ".repeat(900)), "conversation", "run", () -> {});
      assertThat(result.getContents()).contains("current request and Working Set");
      assertThat(calls).hasValue(1);
      assertThat(result.getInstructions().stream().mapToInt(estimator::message).sum())
          .isLessThanOrEqualTo(600);
    }
  }

  /** モデルへ渡す文脈の管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationDoesNotCommitSummaryOrFallBack() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var a =
        assembler((previous, messages, budget, request) -> {
          cancelled.set(true);
          return "summary";
        });
    assertThatThrownBy(() ->
            a.assemble(
                prompt("old ".repeat(500)),
                "conversation",
                "run",
                () -> {
                  if (cancelled.get()) throw new java.util.concurrent.CancellationException();
                }))
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(new ConversationSummaryRepository(dir).read("conversation").throughSequence())
        .isZero();
  }

  /** モデルへ渡す文脈の管理で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void rollingSummarySeesOnlyPreviousSummaryAndNewPrefix() {
    var a =
        assembler((previous, messages, budget, request) -> {
          if (calls.incrementAndGet() == 1) {
            assertThat(previous).isEmpty();
            return "first decision";
          }
          assertThat(previous).isEqualTo("first decision");
          assertThat(messages).extracting(Message::getText).noneMatch(t -> t.contains("old old"));
          assertThat(messages).extracting(Message::getText).anyMatch(t -> t.contains("new new"));
          return "first decision; second decision";
        });
    a.assemble(prompt("old ".repeat(500)), "conversation", "run", () -> {});
    var expanded = new ArrayList<Message>(prompt("old ".repeat(500)).getInstructions());
    expanded.add(3, ContextHistoryAdvisor.historical(new UserMessage("new ".repeat(500)), 3));
    expanded.add(4, ContextHistoryAdvisor.historical(new AssistantMessage("latest"), 4));
    var result = a.assemble(new Prompt(expanded), "conversation", "run", () -> {});
    assertThat(result.getContents()).contains("first decision; second decision", "latest");
    assertThat(calls).hasValue(2);
  }

  /** モデルへ渡す文脈の管理で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentRequestsGenerateOneSummary() throws Exception {
    var a =
        assembler((previous, messages, budget, request) -> {
          calls.incrementAndGet();
          return "shared summary";
        });
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<Prompt>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            executor.submit(
                () -> a.assemble(prompt("old ".repeat(500)), "conversation", "run", () -> {})));
      for (var future : futures) assertThat(future.get().getContents()).contains("shared summary");
    }
    assertThat(calls).hasValue(1);
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void hardLimitDoesNotDiscardCurrentRequestOrSystem() {
    assertThatThrownBy(() ->
            assembler((p, m, b, r) -> "summary")
                .assemble(
                    new Prompt(
                        List.of(
                            new SystemMessage("policy"), new UserMessage("current ".repeat(1000)))),
                    "chat",
                    "run",
                    () -> {}))
        .hasMessageContaining("CONTEXT_HARD_LIMIT");
  }

  /** モデルへ渡す文脈の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void workingSetIsRefreshedAndNeverSentToSummarizer() {
    var a =
        assembler((p, m, b, r) -> {
          assertThat(m).extracting(Message::getText).noneMatch(t -> t.contains("Working Set"));
          return "summary";
        });
    a.setWorkingSet(() -> "Working Set: changed-file.java, next task");
    var messages = new ArrayList<Message>(prompt("old ".repeat(500)).getInstructions());
    messages.add(
        SystemMessage.builder()
            .text("Working Set: obsolete")
            .metadata(Map.of("rei.workingSet", true))
            .build());
    var result = a.assemble(new Prompt(messages), "chat", "run", () -> {});
    assertThat(result.getContents())
        .contains("changed-file.java", "next task")
        .doesNotContain("obsolete");
  }
}
