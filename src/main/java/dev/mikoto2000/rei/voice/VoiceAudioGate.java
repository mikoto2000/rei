package dev.mikoto2000.rei.voice;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 音声による対話に必要な音声音声実行可否の判断を扱う。
 *
 * <p>Half-duplex playback suppression. Epochs also discard recognition started before playback.
 */
public final class VoiceAudioGate {
  /** 音声による対話に必要な再生を扱う。 */
  public interface Playback extends AutoCloseable {
    /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    void close();
  }

  /** 経過時間を測る時刻の取得元。 */
  private final LongSupplier ticks;

  /** 基準時刻。 */
  private long epoch;

  /** 終了日時。 */
  private long until;

  /** 再生中の音の数。 */
  private int playing;

  /** 末尾。 */
  private boolean tail;

  /** 指定された値や利用先を使って、VoiceAudioGateを初期化する。 */
  public VoiceAudioGate(LongSupplier ticks) {
    this.ticks = Objects.requireNonNull(ticks);
  }

  /** 音声による対話で用いる基準時刻を返す。 */
  public synchronized long epoch() {
    return epoch;
  }

  /** 音声による対話において、抑制されたに該当するか判定する。 */
  public synchronized boolean suppressed() {
    if (tail && ticks.getAsLong() - until >= 0) tail = false;
    return playing > 0 || tail;
  }

  /** 音声による対話において、許可に該当するか判定する。 */
  public synchronized boolean allowed(long capturedEpoch) {
    return capturedEpoch == epoch && !suppressed();
  }

  /** 音声による対話において、条件付き許可に該当するか判定する。 */
  public synchronized boolean ifAllowed(long capturedEpoch, Runnable action) {
    if (!allowed(capturedEpoch)) return false;
    action.run();
    return true;
  }

  /** 音声による対話で用いる再生を返す。 */
  public synchronized Playback playback(Duration duration) {
    Objects.requireNonNull(duration);
    if (duration.isNegative() || duration.compareTo(Duration.ofSeconds(3)) > 0)
      throw new IllegalArgumentException("Invalid playback tail");
    long nanos = duration.toNanos();
    playing++;
    epoch++;
    var closed = new AtomicBoolean();
    return () -> {
      // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
      if (!closed.compareAndSet(false, true)) return;
      synchronized (this) {
        playing--;
        long now = ticks.getAsLong();
        long remaining = tail ? Math.max(0, until - now) : 0;
        until = now + Math.max(remaining, nanos);
        tail = true;
      }
    };
  }
}
