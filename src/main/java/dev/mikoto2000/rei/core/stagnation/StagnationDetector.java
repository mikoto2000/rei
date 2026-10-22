package dev.mikoto2000.rei.core.stagnation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 作業が進展していないことを検出する。
 *
 * <p>単純な同一ツール判定ではなく、progress の有無・同一 tool call・同一 failure を組み合わせる。
 */
public class StagnationDetector {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(StagnationDetector.class);

  /** 既定判定の境界値。 */
  static final int DEFAULT_THRESHOLD = 4;

  /** 既定最大計画の見直し回数。 */
  static final int DEFAULT_MAX_REPLANS = 2;

  /** 判定の境界値。 */
  private final int threshold;

  /** 最大計画の見直し回数。 */
  private final int maxReplans;

  /** 停滞件数。 */
  private int stagnationCount;

  /** 計画の見直し件数。 */
  private int replanCount;

  /** 計画の見直し要求された。 */
  private boolean replanRequested;

  /** 直近ツール要求内容の照合値。 */
  private String lastToolFingerprint;

  /** 直近失敗情報要求内容の照合値。 */
  private String lastFailureFingerprint;

  /** repeatedツール呼び出し。 */
  private boolean repeatedToolCall;

  /** repeated失敗情報。 */
  private boolean repeatedFailure;

  /** 実行の停滞への対処で用いる判定の境界値を返す。 */
  public int threshold() {
    return threshold;
  }

  /** 実行の停滞への対処で用いる最大計画の見直し回数を返す。 */
  public int maxReplans() {
    return maxReplans;
  }

  /** 実行の停滞への対処を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public StagnationDetector() {
    this(DEFAULT_THRESHOLD, DEFAULT_MAX_REPLANS);
  }

  /** 実行の停滞への対処を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public StagnationDetector(int threshold) {
    this(threshold, DEFAULT_MAX_REPLANS);
  }

  /** 指定された値や利用先を使って、StagnationDetectorを初期化する。 */
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
    return repeatedToolCall;
  }

  /** 同一 failure が繰り返されたか。 */
  public boolean hasRepeatedFailure() {
    return repeatedFailure;
  }

  /** Counters survive checkpoint resume, including a pending recovery request. */
  public record State(
      int stagnationCount,
      int replanCount,
      boolean replanRequested,
      String lastToolFingerprint,
      String lastFailureFingerprint) {
    public State {
      if (stagnationCount < 0 || replanCount < 0)
        throw new IllegalArgumentException("Invalid detector state");
    }
  }

  public State snapshot() {
    return new State(
        stagnationCount, replanCount, replanRequested, lastToolFingerprint, lastFailureFingerprint);
  }

  public void restore(State state) {
    if (state == null) return;
    stagnationCount = state.stagnationCount();
    replanCount = state.replanCount();
    replanRequested = state.replanRequested();
    lastToolFingerprint = state.lastToolFingerprint();
    lastFailureFingerprint = state.lastFailureFingerprint();
  }

  /** 1 iteration を記録する。progress があればカウントをリセットする。 */
  public void recordIteration(boolean progress) {
    if (progress) {
      stagnationCount = 0;
      replanCount = 0;
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
    if (event == ProgressEvent.FAILURE
        || event == ProgressEvent.TOOL_CALL
        || event == ProgressEvent.CONTEXT_RESTORED
        || event == ProgressEvent.INFORMATION_UNKNOWN) return;
    stagnationCount = 0;
    replanCount = 0;
    replanRequested = false;
    log.debug("Progress detected: {}", event);
  }

  /** replan を記録する。 */
  public void recordReplan() {
    replanCount++;
    stagnationCount = 0;
    replanRequested = false;
    log.debug("Replan recorded: count={}", replanCount);
    if (isMaxReplanReached()) {
      log.debug("Max replans reached: task blocked");
    }
  }

  /** tool call を記録する。完全一致を検出する。 */
  public void recordToolCall(String toolName, String normalizedArguments) {
    String fingerprint = toolName + "|" + normalizedArguments;
    repeatedToolCall = fingerprint.equals(lastToolFingerprint);
    if (fingerprint.equals(lastToolFingerprint)) {
      log.debug("Repeated tool call detected");
    }
    lastToolFingerprint = fingerprint;
  }

  /** failure を記録する。fingerprint は error type + 主要 message。 */
  public void recordFailure(String errorType, String message) {
    String fingerprint = errorType + "|" + message;
    repeatedFailure = fingerprint.equals(lastFailureFingerprint);
    if (fingerprint.equals(lastFailureFingerprint)) {
      log.debug("Repeated failure detected");
    }
    lastFailureFingerprint = fingerprint;
  }
}
