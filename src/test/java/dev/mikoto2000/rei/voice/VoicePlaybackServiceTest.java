package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 音声再生サービスの結果と状態の契約を検証するテスト。 */
class VoicePlaybackServiceTest {
  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  AgentRunContext context() {
    return new AgentRunContext("run", "session", Path.of("."), "project").asVoiceInput();
  }

  /** 音声による対話で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void queueIsBoundedAndStopDiscardsQueuedReplies() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var calls = new AtomicInteger();
    var events = new java.util.concurrent.CopyOnWriteArrayList<VoiceEventPublisher.Event>();
    var publisher = new VoiceEventPublisher();
    publisher.subscribe(events::add);
    VoiceSpeechOutput output =
        (voice, text, current) -> {
          calls.incrementAndGet();
          entered.countDown();
          release.await();
        };
    var options =
        new VoiceAdvancedOptions(
            false, "れい", false, true, VoiceAdvancedOptions.defaults().ttsVoice(), 800);
    try (var service =
        new VoicePlaybackService(
            output, new VoiceAudioGate(System::nanoTime), () -> options, c -> true, publisher)) {
      service.offer(context(), "first");
      assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      service.offer(context(), "second");
      service.offer(context(), "third");
      service.offer(context(), "private fourth");
      assertThat(events).anyMatch(e -> e.type() == VoiceEventPublisher.Type.TTS_DROPPED);
      assertThat(events).noneMatch(e -> e.detail().contains("private"));
      service.stop();
      release.countDown();
    } finally {
      release.countDown();
    }
    assertThat(calls).hasValue(1);
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void defaultOffAndUnownedRepliesNeverReachOutput() {
    var calls = new AtomicInteger();
    var options = new AtomicReference<>(VoiceAdvancedOptions.defaults());
    VoiceSpeechOutput output = (voice, text, current) -> calls.incrementAndGet();
    try (var service =
        new VoicePlaybackService(
            output,
            new VoiceAudioGate(System::nanoTime),
            options::get,
            c -> false,
            new VoiceEventPublisher())) {
      assertThat(service.offer(context(), "hello")).isFalse();
      options.set(
          new VoiceAdvancedOptions(false, "れい", false, true, options.get().ttsVoice(), 800));
      assertThat(service.offer(context(), "hello")).isFalse();
      assertThat(calls).hasValue(0);
    }
  }

  /** 自分が所有する対象音声について、通知の抑制を利用することを確認する。 */
  @Test
  void ownedVoiceUsesSuppressionAndSelectionChangeStopsIt() {
    var gate = new VoiceAudioGate(System::nanoTime);
    var selected = new AtomicBoolean(true);
    var entered = new AtomicBoolean();
    var exited = new AtomicBoolean();
    VoiceSpeechOutput output =
        (voice, text, current) -> {
          assertThat(gate.suppressed()).isTrue();
          entered.set(true);
          while (current.getAsBoolean()) Thread.sleep(10);
          exited.set(true);
        };
    var options =
        new VoiceAdvancedOptions(
            false, "れい", false, true, VoiceAdvancedOptions.defaults().ttsVoice(), 800);
    try (var service =
        new VoicePlaybackService(
            output, gate, () -> options, c -> selected.get(), new VoiceEventPublisher())) {
      assertThat(service.offer(context(), "hello")).isTrue();
      await().atMost(Duration.ofSeconds(2)).until(entered::get);
      selected.set(false);
      await().atMost(Duration.ofSeconds(2)).until(exited::get);
      assertThat(gate.suppressed()).isTrue();
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void keyboardResponsesAndLongMessagesAreNotSpoken() {
    var calls = new AtomicInteger();
    VoiceSpeechOutput output = (voice, text, current) -> calls.incrementAndGet();
    var options =
        new VoiceAdvancedOptions(
            false, "れい", false, true, VoiceAdvancedOptions.defaults().ttsVoice(), 800);
    try (var service =
        new VoicePlaybackService(
            output,
            new VoiceAudioGate(System::nanoTime),
            () -> options,
            c -> true,
            new VoiceEventPublisher())) {
      assertThat(
              service.offer(
                  new AgentRunContext("keyboard", "session", Path.of("."), "project"), "hello"))
          .isFalse();
      assertThat(service.offer(context(), "a".repeat(16385))).isTrue();
      assertThat(calls).hasValue(0);
    }
  }
}
