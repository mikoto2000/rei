package dev.mikoto2000.rei.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.timing.TimingExecution;
import dev.mikoto2000.rei.timing.TimingStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** 時間計測Overhead利用可否の確認の結果と状態の契約を検証するテスト。 */
class TimingOverheadProbeTest {
  /** 検証に使う回答。 */
  final ChatResponse answer =
      new ChatResponse(List.of(new Generation(new AssistantMessage("fixed"))));

  /** 検証に使う委譲先。 */
  final ChatModel delegate =
      new ChatModel() {
        /** 検証用に、用途に応じたモデル利用の要求を実行し、呼び出し元へ結果を返す。 */
        public ChatResponse call(Prompt prompt) {
          return answer;
        }

        /** {@code stream} の条件を再現するための補助情報を返す。 */
        public Flux<ChatResponse> stream(Prompt prompt) {
          return Flux.just(answer);
        }
      };

  /** {@code batch} の条件を再現するための補助情報を返す。 */
  long batch(boolean enabled, int batch) {
    var recorder =
        new TimingStore(
            enabled, 100, 2000, 10000, Duration.ofHours(1), Clock.systemUTC(), System::nanoTime);
    var owner =
        new AgentRunContext("batch-" + batch, "session", java.nio.file.Path.of("."), "project");
    recorder.beginRun(owner.runId(), owner.projectId(), owner.conversationId());
    var prompt =
        new Prompt(
            "fixed",
            OpenAiChatOptions.builder()
                .toolContext(Map.of(AgentRunContext.class.getName(), owner))
                .build());
    var events = new InMemoryAgentEventBus();
    var eventCount = new java.util.concurrent.atomic.AtomicInteger();
    events.subscribe(event -> eventCount.incrementAndGet());
    var model =
        new AgentEventChatModel(
            LlmFeature.CHAT,
            delegate,
            new AgentEventFactory(Clock.systemUTC()),
            events,
            new TimingExecution(recorder));
    long start = System.nanoTime();
    for (int i = 0; i < 1000; i++) assertSame(answer, model.stream(prompt).blockLast());
    long elapsed = System.nanoTime() - start;
    assertEquals(3000, eventCount.get());
    assertEquals(enabled ? 1000 : 0, recorder.statistics().spans());
    return elapsed;
  }

  /** 有効状態および無効状態パス一覧について、出力を保持することを確認する。 */
  @Test
  void enabledAndDisabledPathsPreserveOutputAndEventCountAndReportMeasuredOverhead() {
    for (int i = 0; i < 3; i++) {
      batch(false, -i - 1);
      batch(true, -i - 1);
    }
    long[] disabled = new long[7], enabled = new long[7];
    for (int i = 0; i < 7; i++) {
      disabled[i] = batch(false, i);
      enabled[i] = batch(true, i);
    }
    Arrays.sort(disabled);
    Arrays.sort(enabled);
    System.out.printf(
        Locale.ROOT,
        "TIMING_OVERHEAD stream fixture median 7x1000: disabled=%.3f us/call enabled=%.3f us/call delta=%.3f us/call; synthetic local observation, not real model latency%n",
        disabled[3] / 1_000_000d,
        enabled[3] / 1_000_000d,
        (enabled[3] - disabled[3]) / 1_000_000d);
  }
}
