package dev.mikoto2000.rei.voice;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 音声による対話に必要な音声実行状況の通知先を扱う。
 *
 * <p>Default events contain fixed diagnostic codes. Only explicit /voice test emits recognition
 * text.
 */
public final class VoiceEventPublisher {
  /** 音声による対話における種別の選択肢。 */
  public enum Type {
    /** 補正結果。 */
    CORRECTION_RESULT,
    /** 補正時間計測。 */
    CORRECTION_TIMING,
    /** ASR時間計測。 */
    ASR_TIMING,
    /** 状態変更済み。 */
    STATE_CHANGED,
    /** 区間実行待ち全体。 */
    SEGMENT_QUEUE_FULL,
    /** 短すぎるため除外した発話。 */
    SHORT_DROPPED,
    /** 最大除外された。 */
    MAX_DROPPED,
    /** 中断結果。 */
    INTERRUPT_RESULT,
    /** TTS失敗。 */
    TTS_FAILED,
    /** TTS除外された。 */
    TTS_DROPPED,
    /** 結果拒否済み。 */
    RESULT_REJECTED,
    /** 診断結果。 */
    DIAGNOSTIC_RESULT,
    /** 入力拒否済み。 */
    INPUT_REJECTED,
    /** バックエンド失敗。 */
    BACKEND_FAILED,
    /** 勤務・休息などの生活制約による録音禁止。 */
    PRIVACY_BLOCKED,
    /** 取得記録失敗。 */
    CAPTURE_FAILED,
    /** 認識失敗。 */
    RECOGNITION_FAILED,
    /** 解放失敗。 */
    RELEASE_FAILED,
    /** モデル進捗。 */
    MODEL_PROGRESS,
    /** モデル状態。 */
    MODEL_STATE,
    /** レビュー必須。 */
    REVIEW_REQUIRED,
    /** 対象変更済み。 */
    TARGET_CHANGED,
    /** 取得記録再開済み。 */
    CAPTURE_RESUMED,
    /** DEVICE変更済み。 */
    DEVICE_CHANGED
  }

  /**
   * 音声による対話で受け渡すイベントを表す。
   *
   * @param type 種別
   * @param detail 詳細
   */
  public record Event(Type type, String detail) {}

  /** 通知の受け取り先一覧。 */
  private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

  /** 音声による対話に必要な購読を扱う。 */
  public interface Subscription extends AutoCloseable {
    /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    void close();
  }

  /** 音声による対話で用いる購読を返す。 */
  public Subscription subscribe(Consumer<Event> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  /** 状態や結果を購読先へ通知する。 */
  public void publish(Type type, String detail) {
    var event = new Event(type, detail);
    for (var listener : listeners) {
      try {
        listener.accept(event);
      } catch (RuntimeException ignored) {
        // A UI listener must not kill capture or native-resource cleanup.
      }
    }
  }
}
