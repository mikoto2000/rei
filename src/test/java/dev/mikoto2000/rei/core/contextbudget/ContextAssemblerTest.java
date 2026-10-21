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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 文脈文脈の組み立ての結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ContextAssemblerTest {
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
        assembler(
            (previous, messages, budget, request) -> {
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
        assembler(
            (previous, messages, budget, request) -> {
              cancelled.set(true);
              return "summary";
            });
    assertThatThrownBy(
            () ->
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
        assembler(
            (previous, messages, budget, request) -> {
              if (calls.incrementAndGet() == 1) {
                assertThat(previous).isEmpty();
                return "first decision";
              }
              assertThat(previous).isEqualTo("first decision");
              assertThat(messages)
                  .extracting(Message::getText)
                  .noneMatch(t -> t.contains("old old"));
              assertThat(messages)
                  .extracting(Message::getText)
                  .anyMatch(t -> t.contains("new new"));
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
        assembler(
            (previous, messages, budget, request) -> {
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
    assertThatThrownBy(
            () ->
                assembler((p, m, b, r) -> "summary")
                    .assemble(
                        new Prompt(
                            List.of(
                                new SystemMessage("policy"),
                                new UserMessage("current ".repeat(1000)))),
                        "chat",
                        "run",
                        () -> {}))
        .hasMessageContaining("CONTEXT_HARD_LIMIT");
  }

  /** モデルへ渡す文脈の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void workingSetIsRefreshedAndNeverSentToSummarizer() {
    var a =
        assembler(
            (p, m, b, r) -> {
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
