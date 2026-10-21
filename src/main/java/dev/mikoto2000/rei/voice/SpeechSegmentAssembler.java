package dev.mikoto2000.rei.voice;

import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 音声による対話に必要な発話区間文脈の組み立てを扱う。
 *
 * <p>Bounded assembly from raw VAD probabilities, distinct normal completion and maximum-time drop.
 */
public final class SpeechSegmentAssembler {
  /** 音声による対話における理由の選択肢。 */
  public enum Reason {
    /** 該当なし。 */
    NONE,
    /** 完了。 */
    COMPLETED,
    /** 短すぎるため除外した発話。 */
    SHORT_DROPPED,
    /** 最大除外された。 */
    MAX_DROPPED
  }

  /**
   * 音声による対話で受け渡す判断結果を表す。
   *
   * @param reason 理由
   * @param segment 区間
   */
  public record Decision(Reason reason, SpeechSegment segment) {}

  /** 該当なし。 */
  private static final Decision NONE = new Decision(Reason.NONE, null);

  /** 設定。 */
  private final VoiceSettings settings;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 直近。 */
  private final float[] recent;

  /** 直近続きの取得位置／直近件数。 */
  private int recentCursor, recentCount;

  /** 一時保持領域。 */
  private float[] buffer;

  /** 使用量／経過時間／voiced／直近Voiced／無音。 */
  private int used, age, voiced, lastVoiced, silence;

  /** 抑制された。 */
  private boolean suppressed;

  /** 通知の抑制無音。 */
  private int suppressionSilence;

  /** 指定された値や利用先を使って、SpeechSegmentAssemblerを初期化する。 */
  public SpeechSegmentAssembler(VoiceSettings settings, Clock clock) {
    this.settings = Objects.requireNonNull(settings);
    this.clock = Objects.requireNonNull(clock);
    recent = new float[settings.samples(settings.preRollMs())];
  }

  /** 要求を後続の処理へ引き渡すため、発話区間文脈の組み立てを受け付ける。 */
  public Decision accept(float[] frame, float probability) {
    if (frame == null
        || frame.length == 0
        || frame.length > VoiceSettings.WINDOW
        || !Float.isFinite(probability)
        || probability < 0
        || probability > 1) throw new IllegalArgumentException("Invalid VAD frame");
    for (float sample : frame)
      if (!Float.isFinite(sample) || sample < -1 || sample > 1)
        throw new IllegalArgumentException("Invalid PCM sample");
    try {
      return process(frame, probability >= settings.threshold());
    } finally {
      remember(frame);
    }
  }

  /** 音声による対話で用いるプロセスを返す。 */
  private Decision process(float[] frame, boolean speech) {
    if (suppressed) {
      suppressionSilence = speech ? 0 : suppressionSilence + frame.length;
      if (suppressionSilence >= settings.samples(settings.silenceMs())) {
        suppressed = false;
        suppressionSilence = 0;
      }
      return NONE;
    }
    if (buffer == null) {
      if (!speech) return NONE;
      buffer =
          new float
              [recent.length + settings.samples(settings.maxSpeechMs()) + VoiceSettings.WINDOW];
      int oldest = recentCount < recent.length ? 0 : recentCursor;
      for (int i = 0; i < recentCount; i++) buffer[used++] = recent[(oldest + i) % recent.length];
    }
    System.arraycopy(frame, 0, buffer, used, frame.length);
    used += frame.length;
    age += frame.length;
    if (speech) {
      voiced += frame.length;
      lastVoiced = used;
      silence = 0;
    } else silence += frame.length;
    if (silence >= settings.samples(settings.silenceMs())) {
      if (voiced < settings.samples(settings.minSpeechMs())) {
        discard();
        return new Decision(Reason.SHORT_DROPPED, null);
      }
      int end = Math.min(used, lastVoiced + settings.samples(settings.tailMs()));
      var segment =
          new SpeechSegment(UUID.randomUUID(), Arrays.copyOf(buffer, end), clock.instant());
      discard();
      return new Decision(Reason.COMPLETED, segment);
    }
    if (age >= settings.samples(settings.maxSpeechMs())) {
      int quiet = silence;
      discard();
      suppressed = true;
      suppressionSilence = quiet;
      return new Decision(Reason.MAX_DROPPED, null);
    }
    return NONE;
  }

  /** 音声による対話に関するrememberを処理する。 */
  private void remember(float[] frame) {
    if (recent.length == 0) return;
    for (float sample : frame) {
      recent[recentCursor] = sample;
      recentCursor = (recentCursor + 1) % recent.length;
      recentCount = Math.min(recent.length, recentCount + 1);
    }
  }

  /** 音声による対話に関する破棄を処理する。 */
  private void discard() {
    buffer = null;
    used = age = voiced = lastVoiced = silence = 0;
  }

  /**
   * 新たな処理を開始できるよう、発話区間文脈の組み立てを初期状態へ戻す。
   *
   * <p>Stopping never flushes an incomplete utterance for automatic submission.
   */
  public void reset() {
    discard();
    suppressed = false;
    suppressionSilence = recentCursor = recentCount = 0;
    Arrays.fill(recent, 0);
  }
}
