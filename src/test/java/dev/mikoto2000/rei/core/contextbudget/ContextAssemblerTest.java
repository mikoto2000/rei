package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;

class ContextAssemblerTest {
  @TempDir Path dir;
  final TokenEstimator estimator = TokenEstimator.conservative();
  final AtomicInteger calls = new AtomicInteger();
  ContextCompressionProperties config() {
    var p = new ContextCompressionProperties();
    p.setThreshold(300); p.setHardLimit(600); p.setRecentTokens(80); p.setSummaryTokens(100);
    return p;
  }
  ContextAssembler assembler(ConversationCompressor compressor) {
    return new ContextAssembler(config(), estimator, new ConversationSummaryRepository(dir),
        new ToolResultCompressor(new RawToolResultStore(dir), estimator, 100, 70), compressor, null, null);
  }
  Prompt prompt(String old) {
    return new Prompt(List.of(new SystemMessage("system"),
        ContextHistoryAdvisor.historical(new UserMessage(old), 1),
        ContextHistoryAdvisor.historical(new AssistantMessage("recent"), 2),
        new UserMessage("current request and Working Set")));
  }
  @Test void belowThresholdKeepsOriginalMessages() {
    var p = prompt("short");
    var result = assembler((previous, messages, budget, request) -> { calls.incrementAndGet(); return "summary"; })
        .assemble(p, "conversation", "run", () -> {});
    assertThat(result.getInstructions()).containsExactlyElementsOf(p.getInstructions());
    assertThat(calls).hasValue(0);
  }
  @Test void compressesOnlyOldHistoryAndPersistsCursorAcrossRestart() {
    var p = prompt("old ".repeat(500));
    var result = assembler((previous, messages, budget, request) -> { calls.incrementAndGet(); return "decided API X"; })
        .assemble(p, "conversation", "run", () -> {});
    assertThat(result.getContents()).contains("decided API X", "recent", "current request and Working Set")
        .doesNotContain("old old old");
    assertThat(p.getContents()).contains("old old old");
    assembler((previous, messages, budget, request) -> { calls.incrementAndGet(); return "unexpected"; })
        .assemble(p, "conversation", "next", () -> {});
    assertThat(calls).hasValue(1);
    assertThat(new ConversationSummaryRepository(dir).read("conversation").throughSequence()).isEqualTo(1);
  }
  @Test void failureAndInsufficientGainFallBackWithoutRetry() {
    for (boolean fail : List.of(true, false)) {
      calls.set(0);
      var result = assembler((previous, messages, budget, request) -> {
        calls.incrementAndGet(); if (fail) throw new IllegalStateException("offline");
        return "bad ".repeat(600);
      }).assemble(prompt("old ".repeat(900)), "conversation", "run", () -> {});
      assertThat(result.getContents()).contains("current request and Working Set");
      assertThat(calls).hasValue(1);
      assertThat(result.getInstructions().stream().mapToInt(estimator::message).sum()).isLessThanOrEqualTo(600);
    }
  }
  @Test void cancellationDoesNotCommitSummaryOrFallBack() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var a = assembler((previous, messages, budget, request) -> { cancelled.set(true); return "summary"; });
    assertThatThrownBy(() -> a.assemble(prompt("old ".repeat(500)), "conversation", "run", () -> {
      if (cancelled.get()) throw new java.util.concurrent.CancellationException();
    })).isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(new ConversationSummaryRepository(dir).read("conversation").throughSequence()).isZero();
  }
  @Test void rollingSummarySeesOnlyPreviousSummaryAndNewPrefix() {
    var a = assembler((previous, messages, budget, request) -> {
      if (calls.incrementAndGet() == 1) { assertThat(previous).isEmpty(); return "first decision"; }
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
  @Test void concurrentRequestsGenerateOneSummary() throws Exception {
    var a = assembler((previous, messages, budget, request) -> { calls.incrementAndGet(); return "shared summary"; });
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<Prompt>>();
      for (int i = 0; i < 8; i++) futures.add(executor.submit(() ->
          a.assemble(prompt("old ".repeat(500)), "conversation", "run", () -> {})));
      for (var future : futures) assertThat(future.get().getContents()).contains("shared summary");
    }
    assertThat(calls).hasValue(1);
  }
  @Test void hardLimitDoesNotDiscardCurrentRequestOrSystem() {
    assertThatThrownBy(() -> assembler((p, m, b, r) -> "summary").assemble(new Prompt(List.of(
        new SystemMessage("policy"), new UserMessage("current ".repeat(1000)))), "chat", "run", () -> {}))
        .hasMessageContaining("CONTEXT_HARD_LIMIT");
  }
  @Test void workingSetIsRefreshedAndNeverSentToSummarizer() {
    var a = assembler((p, m, b, r) -> {
      assertThat(m).extracting(Message::getText).noneMatch(t -> t.contains("Working Set"));
      return "summary";
    });
    a.setWorkingSet(() -> "Working Set: changed-file.java, next task");
    var messages = new ArrayList<Message>(prompt("old ".repeat(500)).getInstructions());
    messages.add(SystemMessage.builder().text("Working Set: obsolete")
        .metadata(Map.of("rei.workingSet", true)).build());
    var result = a.assemble(new Prompt(messages), "chat", "run", () -> {});
    assertThat(result.getContents()).contains("changed-file.java", "next task").doesNotContain("obsolete");
  }
}
