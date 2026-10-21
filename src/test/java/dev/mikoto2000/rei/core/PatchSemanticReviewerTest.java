package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 変更差分意味によるReviewerの結果と状態の契約を検証するテスト。 */
class PatchSemanticReviewerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証用に、共通の実行基盤の操作を実行する。 */
  RunExecutionContext run(int calls) {
    var run =
        new RunExecutionContext("run", new OutputLimitRunBudget(0, calls), null, null, event -> {});
    run.setRunContext(new AgentRunContext("run", "session", root, UUID.randomUUID().toString()));
    return run;
  }

  /** {@code input} の条件を再現するための補助情報を返す。 */
  SemanticPatchReviewService.Input input() {
    return new SemanticPatchReviewService.Input(
        "check required value",
        List.of(
            new SemanticPatchReviewService.Requirement(
                "R1", "required value", List.of("A.java"), List.of("ATest#value"))),
        "a".repeat(64),
        "untrusted diff",
        Map.of("A.java", "untrusted source"),
        List.of());
  }

  /** {@code valid} の条件を再現するための補助情報を返す。 */
  String valid() {
    return "{\"status\":\"MATCH\",\"requirements\":{\"R1\":\"PASS\"},\"dimensions\":{\"requirements\":\"PASS\",\"extraChanges\":\"PASS\",\"security\":\"PASS\",\"tests\":\"PASS\",\"hygiene\":\"PASS\",\"compatibility\":\"PASS\"}}";
  }

  /** 検証で使用するモデルを、指定された条件に合わせて用意する。 */
  ChatModel model(AtomicInteger calls, String output) {
    return new ChatModel() {
      /** 検証用に、共通の実行基盤の要求を実行し、呼び出し元へ結果を返す。 */
      public ChatResponse call(Prompt prompt) {
        throw new AssertionError("stream only");
      }

      /** {@code stream} の条件を再現するための補助情報を返す。 */
      public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
        calls.incrementAndGet();
        var options = (OpenAiChatOptions) prompt.getOptions();
        assertTrue(options.getToolCallbacks().isEmpty());
        assertEquals("none", options.getToolChoice());
        assertEquals(Set.of(AgentRunContext.class.getName()), options.getToolContext().keySet());
        assertTrue(prompt.getInstructions().getFirst().getText().contains("untrusted"));
        return reactor.core.publisher.Flux.just(
            new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
      }
    };
  }

  /** 共通の実行基盤で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void oneToolFreeCallConsumesParentBudgetAndExhaustionCannotInvokeAgain() throws Exception {
    var calls = new AtomicInteger();
    var reviewer = new PatchSemanticReviewer(() -> model(calls, valid()));
    var run = run(1);
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    assertEquals("MATCH", reviewer.judge(input(), run, deadline).status());
    assertEquals(0, run.sharedLlmReservation().remaining());
    assertEquals("UNKNOWN", reviewer.judge(input(), run, deadline).status());
    assertEquals(1, calls.get());
  }

  /** 共通の実行基盤で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void malformedDuplicateTrailingAndOversizedVerdictsAbstain() throws Exception {
    for (String output :
        List.of(
            valid() + " {}",
            valid().replace("\"status\":\"MATCH\"", "\"status\":\"MATCH\",\"status\":\"FAIL\""),
            "x".repeat(4097),
            "{\"status\":\"MATCH\"}")) {
      var calls = new AtomicInteger();
      assertEquals(
          "UNKNOWN",
          new PatchSemanticReviewer(() -> model(calls, output))
              .judge(input(), run(1), System.nanoTime() + Duration.ofSeconds(5).toNanos())
              .status());
      assertEquals(1, calls.get());
    }
  }

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void ambientToolsAndCancellationDoNotReachModel() throws Exception {
    var calls = new AtomicInteger();
    var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    ChatModel unsafe =
        new ChatModel() {
          /** 検証用に、共通の実行基盤の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            fail("no model");
            return null;
          }

          /** 検証用に、共通の実行基盤で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return OpenAiChatOptions.builder().toolCallbacks(List.of(callback)).build();
          }
        };
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PatchSemanticReviewer(() -> unsafe)
                .judge(input(), run(1), System.nanoTime() + Duration.ofSeconds(5).toNanos()));
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              new PatchSemanticReviewer(() -> model(calls, valid()))
                  .judge(input(), run(1), System.nanoTime() + Duration.ofSeconds(5).toNanos()));
    } finally {
      Thread.interrupted();
    }
    assertEquals(0, calls.get());
  }
}
