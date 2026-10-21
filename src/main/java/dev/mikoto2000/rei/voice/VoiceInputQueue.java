package dev.mikoto2000.rei.voice;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 音声による対話に必要な音声入力からの依頼かどうか実行待ちを扱う。
 *
 * <p>Bounded complete-segment queue; offer never waits for ASR or an Agent.
 */
public final class VoiceInputQueue {
  /** 実行待ち。 */
  private final ArrayBlockingQueue<SpeechSegment> queue = new ArrayBlockingQueue<>(2);

  /** 音声による対話において、受付に該当するか判定する。 */
  public boolean offer(SpeechSegment segment) {
    return queue.offer(segment);
  }

  /** 音声による対話で用いる状態確認を返す。 */
  public SpeechSegment poll(long timeout, TimeUnit unit) throws InterruptedException {
    return queue.poll(timeout, unit);
  }

  /** 音声による対話で用いる大きさを返す。 */
  public int size() {
    return queue.size();
  }

  /** 保持している情報を初期状態へ戻すため、音声入力からの依頼かどうか実行待ちを消去する。 */
  public void clear() {
    queue.clear();
  }
}
