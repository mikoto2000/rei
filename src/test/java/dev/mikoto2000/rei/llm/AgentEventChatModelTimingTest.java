package dev.mikoto2000.rei.llm;

import static dev.mikoto2000.rei.timing.TimingRecorder.Category;
import static dev.mikoto2000.rei.timing.TimingRecorder.Metric;
import static dev.mikoto2000.rei.timing.TimingRecorder.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.timing.TimingExecution;
import dev.mikoto2000.rei.timing.TimingStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** エージェントイベント応答生成に使うモデル時間計測の結果と状態の契約を検証するテスト。 */
class AgentEventChatModelTimingTest {
  /** 検証に使う定期確認。 */
  final AtomicLong tick = new AtomicLong();

  /** 検証に使う保存先。 */
  final TimingStore store =
      new TimingStore(true, 10, 20, 100, Duration.ofHours(1), Clock.systemUTC(), tick::get);

  /** 検証に使う所有者。 */
  final AgentRunContext owner =
      new AgentRunContext(
          "run",
          "session",
          java.nio.file.Path.of("."),
          "project",
          AgentRunContext.RequestSource.SHELL);

  /** 検証に使うプロンプト。 */
  final Prompt prompt =
      new Prompt(
          "PROMPT-SECRET",
          OpenAiChatOptions.builder()
              .toolContext(Map.of(AgentRunContext.class.getName(), owner))
              .build());

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  /** 検証で使用するモデルを、指定された条件に合わせて用意する。 */
  AgentEventChatModel model(ChatModel delegate) {
    store.beginRun("run", "project", "session");
    return new AgentEventChatModel(
        LlmFeature.CHAT,
        delegate,
        new AgentEventFactory(Clock.systemUTC()),
        new InMemoryAgentEventBus(),
        new TimingExecution(store));
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void emptyFirstChunkIsDistinctFromFirstTextAndDoesNotInventGeneratedTokenOrTps() {
    var delegate = mock(ChatModel.class);
    var empty = response("");
    var text = response("RESPONSE-SECRET");
    when(delegate.stream(prompt))
        .thenReturn(
            Flux.defer(
                    () -> {
                      tick.set(4);
                      return Flux.just(empty);
                    })
                .concatWith(
                    Flux.defer(
                        () -> {
                          tick.set(9);
                          return Flux.just(text);
                        })));
    assertEquals(List.of(empty, text), model(delegate).stream(prompt).collectList().block());
    var run = store.latest("project", "session").orElseThrow();
    var span = run.spans().getFirst();
    assertEquals(4, span.milestones().get(Metric.FIRST_FRAMEWORK_CHUNK));
    assertEquals(9, span.milestones().get(Metric.FIRST_GENERATION_TEXT));
    assertFalse(span.milestones().containsKey(Metric.FIRST_GENERATED_TOKEN));
    assertNull(span.generationTps());
    assertEquals(Status.SUCCESS, span.status());
    assertFalse(run.toString().contains("SECRET"));
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void synchronousAuxiliaryCallInRunScopeIsMeasuredWithoutToolContext() {
    var delegate = mock(ChatModel.class);
    var plain = new Prompt("AUXILIARY-SECRET");
    when(delegate.call(plain)).thenReturn(response("OK"));
    try (var scope = AgentRunScope.open(owner)) {
      model(delegate).call(plain);
    }
    var spans = store.latest("project", "session").orElseThrow().spans();
    assertEquals(1, spans.size());
    assertFalse(spans.toString().contains("SECRET"));
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void knownUsageIsRecordedWithoutUsingTextLengthAsTokens() {
    var delegate = mock(ChatModel.class);
    var value =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("SECRET"))),
            ChatResponseMetadata.builder().usage(new DefaultUsage(2, 3)).build());
    when(delegate.call(prompt)).thenReturn(value);
    assertSame(value, model(delegate).call(prompt));
    var span = store.latest("project", "session").orElseThrow().spans().getFirst();
    assertEquals(2, span.inputTokens());
    assertEquals(3, span.outputTokens());
    assertNull(span.generatedTokens());
  }

  /** 用途に応じたモデル利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void partialFailureAndUnfinishedCancellationDoNotBecomeSuccessful() {
    var delegate = mock(ChatModel.class);
    when(delegate.stream(prompt))
        .thenReturn(
            Flux.just(response("SECRET"))
                .concatWith(Flux.error(new IllegalStateException("ERROR-SECRET"))));
    assertThrows(IllegalStateException.class, () -> model(delegate).stream(prompt).blockLast());
    assertEquals(
        Status.FAILED,
        store.latest("project", "session").orElseThrow().spans().getFirst().status());
  }

  /** 既存の手順予算を保持することを確認する。 */
  @Test
  void retryGroupsPhysicalAttemptsAndPreservesExistingStepBudget() {
    var delegate = mock(ChatModel.class);
    when(delegate.stream(prompt))
        .thenReturn(
            Flux.error(new com.openai.errors.OpenAIRetryableException("PRIVATE")),
            Flux.just(response("OK")));
    var retries = new dev.mikoto2000.rei.core.chat.BoundedToolLoop.ModelRetries(1);
    assertEquals(
        "OK",
        new dev.mikoto2000.rei.core.chat.BoundedToolLoop()
            .runWithHistory(
                model(delegate),
                prompt,
                new java.util.concurrent.atomic.AtomicInteger(2),
                owner,
                () -> {},
                null,
                retries)
            .block()
            .output());
    var spans = store.latest("project", "session").orElseThrow().spans();
    var llm = spans.stream().filter(s -> s.category() == Category.LLM).toList();
    assertEquals(2, llm.size());
    assertEquals(llm.getFirst().requestId(), llm.getLast().requestId());
    assertNotEquals(llm.getFirst().attemptId(), llm.getLast().attemptId());
    assertEquals(1, spans.stream().filter(s -> s.category() == Category.RETRY).count());
    assertEquals(1, retries.attempts());
    verify(delegate, times(2)).stream(prompt);
  }

  /** 用途に応じたモデル利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void subscriberDisconnectionIsRecordedWithoutChangingCancellation() {
    var delegate = mock(ChatModel.class);
    when(delegate.stream(prompt)).thenReturn(Flux.never());
    var subscription = model(delegate).stream(prompt).subscribe();
    subscription.dispose();
    assertEquals(
        Status.DISCONNECTED,
        store.latest("project", "session").orElseThrow().spans().getFirst().status());
  }

  /** 用途に応じたモデル利用で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void summaryPurposeIsRecordedForCallAndStream(boolean streaming) {
    var context =
        Map.<String, Object>of(
            AgentRunContext.class.getName(), owner, "rei.modelCallPurpose", "context-summary");
    var summary =
        new Prompt("SUMMARY-SECRET", OpenAiChatOptions.builder().toolContext(context).build());
    var delegate = mock(ChatModel.class);
    var result = response("OK");
    when(delegate.call(summary)).thenReturn(result);
    when(delegate.stream(summary)).thenReturn(Flux.just(result));
    var wrapped = model(delegate);
    if (streaming) wrapped.stream(summary).blockLast();
    else wrapped.call(summary);
    var span = store.latest("project", "session").orElseThrow().spans().getFirst();
    assertEquals("context-summary", span.llm().feature());
    assertFalse(span.toString().contains("SUMMARY-SECRET"));
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownPurposeDoesNotEnterTimingLabel() {
    var context =
        Map.<String, Object>of(
            AgentRunContext.class.getName(), owner, "rei.modelCallPurpose", "PRIVATE-PURPOSE");
    var unknown = new Prompt("SECRET", OpenAiChatOptions.builder().toolContext(context).build());
    var delegate = mock(ChatModel.class);
    when(delegate.call(unknown)).thenReturn(response("OK"));
    model(delegate).call(unknown);
    var span = store.latest("project", "session").orElseThrow().spans().getFirst();
    assertEquals(LlmFeature.CHAT, span.llm().feature());
    assertFalse(span.toString().contains("PRIVATE-PURPOSE"));
  }
}
