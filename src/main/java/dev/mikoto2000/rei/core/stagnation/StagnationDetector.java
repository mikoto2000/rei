package dev.mikoto2000.rei.core.stagnation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 作業が進展していないことを検出する。
 *
 * <p>単純な同一ツール判定ではなく、progress の有無・同一 tool call・同一 failure を組み合わせる。</p>
 */
public class StagnationDetector {

  private static final Logger log = LoggerFactory.getLogger(StagnationDetector.class);

  static final int DEFAULT_THRESHOLD = 4;
  static final int DEFAULT_MAX_REPLANS = 2;

  private final int threshold;
  private final int maxReplans;
  private int stagnationCount;
  private int replanCount;
  private boolean replanRequested;
  private String lastToolFingerprint;
  private String lastFailureFingerprint;

  public StagnationDetector() {
    this(DEFAULT_THRESHOLD, DEFAULT_MAX_REPLANS);
  }

  public StagnationDetector(int threshold) {
    this(threshold, DEFAULT_MAX_REPLANS);
  }

  public StagnationDetector(int threshold, int maxReplans) {
    this.threshold = Math.max(1, threshold);
    this.maxReplans = Math.max(1, maxReplans);
  }

  /** 現在の stagnation カウント。 */
  public int stagnationCount() {
    return stagnationCount;
  }

  /** 現在の replan 回数。 */
  public int replanCount() {
    return replanCount;
  }

  /** stagnation 判定（threshold 到達）。 */
  public boolean isStagnant() {
    return stagnationCount >= threshold;
  }

  /** replan が要求されているか。 */
  public boolean isReplanRequested() {
    return replanRequested;
  }

  /** max replan に到達したか。 */
  public boolean isMaxReplanReached() {
    return replanCount >= maxReplans;
  }

  /** 同一 tool call が繰り返されたか。 */
  public boolean hasRepeatedToolCall() {
    return lastToolFingerprint != null;
  }

  /** 同一 failure が繰り返されたか。 */
  public boolean hasRepeatedFailure() {
    return lastFailureFingerprint != null;
  }

  /** 1 iteration を記録する。progress があればカウントをリセットする。 */
  public void recordIteration(boolean progress) {
    if (progress) {
      stagnationCount = 0;
      replanRequested = false;
      log.debug("Progress detected");
    } else {
      stagnationCount++;
      log.debug("No progress: count={}", stagnationCount);
      if (isStagnant()) {
        replanRequested = true;
        log.debug("Stagnation detected: requesting replan");
      }
    }
  }

  /** 進展イベントを記録する。 */
  public void recordProgress(ProgressEvent event) {
    stagnationCount = 0;
    replanRequested = false;
    log.debug("Progress detected: {}", event);
  }

  /** replan を記録する。 */
  public void recordReplan() {
    replanCount++;
    replanRequested = false;
    log.debug("Replan recorded: count={}", replanCount);
    if (isMaxReplanReached()) {
      log.debug("Max replans reached: task blocked");
    }
  }

  /** tool call を記録する。完全一致を検出する。 */
  public void recordToolCall(String toolName, String normalizedArguments) {
    String fingerprint = toolName + "|" + normalizedArguments;
    if (fingerprint.equals(lastToolFingerprint)) {
      log.debug("Repeated tool call detected");
    }
    lastToolFingerprint = fingerprint;
  }

  /** failure を記録する。fingerprint は error type + 主要 message。 */
  public void recordFailure(String errorType, String message) {
    String fingerprint = errorType + "|" + message;
    if (fingerprint.equals(lastFailureFingerprint)) {
      log.debug("Repeated failure detected");
    }
    lastFailureFingerprint = fingerprint;
  }
}
