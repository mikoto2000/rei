package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 音声再生のユースケースを実現する。
 *
 * <p>Bounded optional narration of the captured Shell owner's completed VOICE replies.
 */
public final class VoicePlaybackService implements AutoCloseable {
  /** 出力。 */
  private final VoiceSpeechOutput output;

  /** 実行可否の判断。 */
  private final VoiceAudioGate gate;

  /** 選択可能な設定。 */
  private final Supplier<VoiceAdvancedOptions> options;

  /** 選択中。 */
  private final Predicate<AgentRunContext> selected;

  /** イベント一覧。 */
  private final VoiceEventPublisher events;

  /** 生成。 */
  private final AtomicLong generation = new AtomicLong();

  /** 実行の担当。 */
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          r -> {
            var t = new Thread(r, "voice-playback");
            t.setDaemon(true);
            return t;
          });

  /** 指定された値や利用先を使って、VoicePlaybackServiceを初期化する。 */
  public VoicePlaybackService(
      VoiceSpeechOutput output,
      VoiceAudioGate gate,
      Supplier<VoiceAdvancedOptions> options,
      Predicate<AgentRunContext> selected,
      VoiceEventPublisher events) {
    this.output = Objects.requireNonNull(output);
    this.gate = Objects.requireNonNull(gate);
    this.options = Objects.requireNonNull(options);
    this.selected = Objects.requireNonNull(selected);
    this.events = Objects.requireNonNull(events);
  }

  /** 音声による対話において、有効状態対象に該当するか判定する。 */
  public boolean enabledFor(AgentRunContext context) {
    return context != null
        && context.voiceInput()
        && context.requestSource() == AgentRunContext.RequestSource.SHELL
        && options.get().ttsEnabled();
  }

  /** 音声による対話において、対象の条件に該当するか判定する。 */
  private boolean eligible(AgentRunContext context) {
    return enabledFor(context) && selected.test(context) && !worker.isShutdown();
  }

  /**
   * 音声による対話において、受付に該当するか判定する。
   *
   * <p>True means this opt-in handled the reply, including an explicit bounded drop.
   */
  public boolean offer(AgentRunContext context, String text) {
    if (!eligible(context) || text == null || text.isBlank()) return false;
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (text.length() > 16384) {
      events.publish(
          VoiceEventPublisher.Type.TTS_DROPPED, "reply too long; see displayed response");
      return true;
    }
    long epoch = generation.get();
    try {
      worker.execute(
          () -> {
            BooleanSupplier current = () -> epoch == generation.get() && eligible(context);
            if (!current.getAsBoolean()) return;
            var settings = options.get();
            try (var playback = gate.playback(Duration.ofMillis(settings.echoTailMs()))) {
              if (current.getAsBoolean()) output.speak(settings.ttsVoice(), text, current);
            } catch (InterruptedException interrupted) {
              // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
              Thread.currentThread().interrupt();
            } catch (Exception failure) {
              events.publish(VoiceEventPublisher.Type.TTS_FAILED, "local playback failed");
            }
          });
    } catch (RejectedExecutionException full) {
      events.publish(VoiceEventPublisher.Type.TTS_DROPPED, "playback queue full");
    }
    return true;
  }

  /** 実行中の処理を停止する。 */
  public void stop() {
    generation.incrementAndGet();
    worker.getQueue().clear();
    output.stop();
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    stop();
    worker.shutdownNow();
    try {
      worker.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    output.close();
  }
}
