package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** 停滞応答生成に使うモデルの結果と状態の契約を検証するテスト。 */
class StagnationChatModelTest {
  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void completedToolBatchSuspendsBeforeAnotherModelCall() {
    var context = context(5, new ArrayList<>());
    var calls = new AtomicInteger();
    var saved = new AtomicInteger();
    context.setContinuation(
        new dev.mikoto2000.rei.core.chat.RunContinuation() {
          /** {@code restore} の条件を再現するための補助情報を返す。 */
          public Saved restore(dev.mikoto2000.rei.core.chat.AgentRunContext owner) {
            return null;
          }

          /** {@code suspend} の条件を再現するための補助情報を返す。 */
          public boolean suspend(RunExecutionContext execution, List<Message> history) {
            assertThat(history.getLast()).isInstanceOf(ToolResponseMessage.class);
            saved.incrementAndGet();
            return true;
          }
        });
    assertThatThrownBy(
            () ->
                model(new ArrayList<>(), calls, false).stream(prompt(context, () -> "WAITING"))
                    .blockLast())
        .isInstanceOf(dev.mikoto2000.rei.core.chat.RunWaitingException.class);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(saved.get()).isEqualTo(1);
  }

  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void acceptsUnsetCallbacksAndDoesNotMutateRequestContext() {
    var context = context(3, new ArrayList<>());
    var options =
        org.springframework.ai.openai.OpenAiChatOptions.builder()
            .model("configured-model")
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build();
    ChatModel delegate =
        new ChatModel() {
          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            var actual = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getOptions();
            assertThat(actual.getModel()).isEqualTo("configured-model");
            assertThat(actual.getToolCallbacks()).isEmpty();
            assertThat(actual.getToolContext()).containsKey("rei.contextSequenceOffset");
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
          }
        };
    assertThat(
            new StagnationChatModel(delegate)
                .stream(new Prompt("work", options)).blockLast().getResult().getOutput().getText())
        .isEqualTo("done");
    assertThat(options.getToolContext()).doesNotContainKey("rei.contextSequenceOffset");
    assertThat(options.getToolCallbacks()).isNull();
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void policyBlocksAChatToolBeforeTheCallbackIsInvoked() {
    var events = new ArrayList<AgentEvent>();
    var context = context(3, events);
    var p =
        new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
            new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(
                true, Set.of(), Set.of(), Map.of()));
    context.setToolPermissionGuard(
        new dev.mikoto2000.rei.core.policy.ToolPermissionGuard(
            p, new AgentEventFactory(Clock.systemUTC()), events::add));
    var effects = new AtomicInteger();
    assertThatThrownBy(
            () ->
                model(new ArrayList<>(), new AtomicInteger(), false).stream(
                        prompt(
                            context,
                            () -> {
                              effects.incrementAndGet();
                              return "content";
                            }))
                    .blockLast())
        .hasMessageContaining("REQUIRE_APPROVAL");
    assertThat(effects.get()).isZero();
    assertThat(events).anyMatch(e -> e.type() == AgentEventType.TOOL_FAILED);
  }

  /** 実行の停滞への対処で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void modelFailureStillEndsTheIteration() {
    var context = context(3, new ArrayList<>());
    ChatModel failing =
        new ChatModel() {
          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            return Flux.error(new IllegalStateException("network failed"));
          }
        };
    assertThatThrownBy(
            () ->
                new StagnationChatModel(failing)
                    .stream(prompt(context, () -> "unused")).blockLast())
        .hasMessageContaining("network failed");
    assertThat(context.detector().stagnationCount()).isEqualTo(1);
  }

  /** 実行の停滞への対処の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledRunCannotExecuteAnotherToolOrLlmCall() {
    var context = context(5, new ArrayList<>());
    context.close();
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                model(new ArrayList<>(), calls, false).stream(prompt(context, () -> "unused"))
                    .blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    assertThat(calls.get()).isZero();
  }

  /** 実行の停滞への対処で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void outputLimitsWithVerifiedProgressContinueWithoutSpendingReplans() {
    var calls = new AtomicInteger();
    var context = context(10, new ArrayList<>());
    ChatModel delegate =
        new ChatModel() {
          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            int n = calls.incrementAndGet();
            if (n > 7) return Flux.error(new IllegalStateException("unexpected call"));
            AssistantMessage message =
                n % 2 == 1 && n < 7
                    ? AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "id" + n, "function", "readFile", "{}")))
                        .build()
                    : new AssistantMessage(n == 7 ? "done" : "partial");
            String reason = n == 7 ? "stop" : n % 2 == 0 ? "length" : "tool_calls";
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            message,
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason(reason)
                                .build()))));
          }
        };
    var output =
        new StagnationChatModel(delegate)
            .stream(prompt(context, () -> "content" + calls.get())).collectList().block();
    assertThat(output.getLast().getResult().getOutput().getText()).isEqualTo("done");
    assertThat(calls.get()).isEqualTo(7);
    assertThat(context.detector().replanCount()).isZero();
  }

  /** 実行の停滞への対処で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void batchOfFourDuplicateToolsIsOneIteration() {
    var calls = new AtomicInteger();
    var context = context(3, new ArrayList<>());
    ChatModel delegate =
        new ChatModel() {
          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            int n = calls.incrementAndGet();
            var batch =
                java.util.stream.IntStream.range(0, 4)
                    .mapToObj(
                        i ->
                            new AssistantMessage.ToolCall(
                                n + "-" + i, "function", "readFile", "{}"))
                    .toList();
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            AssistantMessage.builder().content("").toolCalls(batch).build()))));
          }
        };
    assertThatThrownBy(
            () ->
                new StagnationChatModel(delegate).stream(prompt(context, () -> "same")).blockLast())
        .hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
    assertThat(context.detector().stagnationCount()).isEqualTo(2);
  }

  /** 繰り返された成功済みのについて、停止するを読み取ることを確認する。 */
  @Test
  void repeatedSuccessfulReadStopsAfterTwoUnsuccessfulReplans() {
    var calls = new AtomicInteger();
    List<Prompt> prompts = new ArrayList<>();
    List<AgentEvent> events = new ArrayList<>();
    var context = context(30, events);
    var model = model(prompts, calls, false);
    assertThatThrownBy(() -> model.stream(prompt(context, () -> "same contents")).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("STAGNATED");
    assertThat(calls.get()).isEqualTo(13); // first read is new, then 3 x 4 without progress
    assertThat(prompts.stream().filter(p -> p.getContents().contains("Replan Notice")).count())
        .isGreaterThanOrEqualTo(2);
    assertThat(
            events.stream()
                .filter(e -> e.type() == AgentEventType.STAGNATION_REPLAN_REQUESTED)
                .count())
        .isEqualTo(2);
    assertThat(events.getLast().type()).isEqualTo(AgentEventType.STAGNATION_STOPPED);
  }

  /** 新規情報について、実行中終了日時厳格な言語モデル予算を維持することを確認する。 */
  @Test
  void newInformationKeepsRunningUntilHardLlmBudget() {
    var calls = new AtomicInteger();
    var results = new AtomicInteger();
    List<AgentEvent> events = new ArrayList<>();
    var context = context(6, events);
    assertThatThrownBy(
            () ->
                model(new ArrayList<>(), calls, false).stream(
                        prompt(context, () -> "new content " + results.incrementAndGet()))
                    .blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
    assertThat(calls.get()).isEqualTo(6);
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.STAGNATION_REPLAN_REQUESTED);
  }

  /** 復旧変更後計画の見直しについて、通常完了を許可することを確認する。 */
  @Test
  void recoveryAfterReplanAllowsNormalCompletion() {
    var calls = new AtomicInteger();
    List<AgentEvent> events = new ArrayList<>();
    var context = context(30, events);
    var response =
        model(new ArrayList<>(), calls, true).stream(
                prompt(context, () -> calls.get() < 6 ? "same" : "new"))
            .collectList()
            .block();
    assertThat(response.getLast().getResult().getOutput().getText()).isEqualTo("done");
    assertThat(events).anyMatch(e -> e.type() == AgentEventType.STAGNATION_RECOVERED);
    assertThat(context.detector().replanCount()).isZero();
  }

  /** 実行の停滞への対処で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void guidanceDuringToolIsAppliedAfterResultAndBeforeNextRequest() {
    var context = context(10, new ArrayList<>());
    var queue = new dev.mikoto2000.rei.core.chat.UserInterventionQueue();
    List<String> history = new ArrayList<>();
    context.setInterventions(queue, history::add);
    List<Prompt> requests = new ArrayList<>();
    var delegate =
        new ChatModel() {
          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            requests.add(p);
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            requests.size() == 1
                                ? AssistantMessage.builder()
                                    .content("")
                                    .toolCalls(
                                        List.of(
                                            new AssistantMessage.ToolCall(
                                                "id", "function", "readFile", "{}")))
                                    .build()
                                : new AssistantMessage("done")))));
          }
        };
    new StagnationChatModel(delegate)
        .stream(
                prompt(
                    context,
                    () -> {
                      queue.offer("do not change README");
                      queue.offer("keep API");
                      assertThat(history).isEmpty();
                      return "tool succeeded";
                    }))
            .blockLast();
    assertThat(history).containsExactly("do not change README", "keep API");
    assertThat(requests).hasSize(2);
    assertThat(requests.get(1).getInstructions()).anyMatch(m -> m instanceof ToolResponseMessage);
    assertThat(
            requests.get(1).getInstructions().stream()
                .filter(m -> m instanceof UserMessage)
                .map(Message::getText))
        .containsSubsequence("do not change README", "keep API");
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private RunExecutionContext context(int limit, List<AgentEvent> events) {
    var budget = new OutputLimitRunBudget(2, limit);
    budget.tryConsumeLlmCall(); // top-level prompt is reserved by ChatExecutionService
    return new RunExecutionContext(
        "test-run",
        budget,
        new ProgressEvaluator(Path.of(".")),
        new AgentEventFactory(Clock.systemUTC()),
        events::add);
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  private Prompt prompt(RunExecutionContext context, java.util.function.Supplier<String> result) {
    ToolCallback callback =
        new ToolCallback() {
          /** 検証用に、実行の停滞への対処で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("readFile")
                .description("read")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            return result.get();
          }

          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input, ToolContext toolContext) {
            return call(input);
          }
        };
    return new Prompt(
        "work",
        ToolCallingChatOptions.builder()
            .toolCallbacks(callback)
            .toolContext(Map.of(RunExecutionContext.KEY, context))
            .build());
  }

  /** 検証で使用するモデルを、指定された条件に合わせて用意する。 */
  private StagnationChatModel model(List<Prompt> prompts, AtomicInteger calls, boolean finish) {
    ChatModel delegate =
        new ChatModel() {
          /** 検証用に、実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                .hasSize(1);
            prompts.add(prompt);
            int n = calls.incrementAndGet();
            AssistantMessage message =
                finish && n == 7
                    ? new AssistantMessage("done")
                    : AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "call-" + n, "function", "readFile", "{\"path\":\"A\"}")))
                        .build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
          }
        };
    return new StagnationChatModel(delegate);
  }
}
