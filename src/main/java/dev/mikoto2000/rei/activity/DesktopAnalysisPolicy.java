package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * デスクトップ分析の判断基準をまとめる。
 *
 * <p>Bounded low-priority scheduling policy; no timers or screenshot retention.
 */
final class DesktopAnalysisPolicy {
  /** この機能の動作設定。 */
  private final ActivityProperties p;

  /** 開始／実行一覧／switches。 */
  private final Deque<Instant> starts = new ArrayDeque<>(),
      executions = new ArrayDeque<>(),
      switches = new ArrayDeque<>();

  /** 直近／直近実行。 */
  private Instant last, lastExecution;

  /** 照合キー／候補／範囲。 */
  private String key, candidate, window;

  /** 指定された値や利用先を使って、DesktopAnalysisPolicyを初期化する。 */
  DesktopAnalysisPolicy(ActivityProperties p) {
    this.p = p;
  }

  /** 新たな処理を開始できるよう、デスクトップ分析判断基準を初期状態へ戻す。 */
  void reset() {
    starts.clear();
    executions.clear();
    switches.clear();
    last = null;
    lastExecution = null;
    candidate = null;
    key = null;
    window = null;
  }

  /** 作業状況の把握に関する範囲を処理する。 */
  void window(Instant at, String id) {
    if (window != null && !Objects.equals(window, id)) switches.addLast(at);
    window = id;
    while (!switches.isEmpty() && !switches.getFirst().isAfter(at.minusSeconds(60)))
      switches.removeFirst();
    while (switches.size() > 4) switches.removeFirst();
  }

  /** 作業状況の把握において、期限に該当するか判定する。 */
  boolean due(Instant at, boolean idle, String context) {
    while (!starts.isEmpty() && !starts.getFirst().isAfter(at.minusSeconds(3600)))
      starts.removeFirst();
    boolean changed = key != null && !Objects.equals(key, context);
    candidate = context;
    if (!p.getDetection().isBackgroundFullScreenEnabled() || idle || starts.size() >= 6)
      return false;
    if (last == null) return true;
    if (at.isBefore(last.plusSeconds(60))) return false;
    return !at.isBefore(last.plusSeconds(Math.max(60, p.getBackgroundAnalysisIntervalSeconds())))
        || changed
        || switches.size() >= 3;
  }

  /** 作業状況の把握において、実行許可に該当するか判定する。 */
  boolean executionAllowed(Instant at) {
    while (!executions.isEmpty() && !executions.getFirst().isAfter(at.minusSeconds(3600)))
      executions.removeFirst();
    return executions.size() < 6
        && (lastExecution == null || !at.isBefore(lastExecution.plusSeconds(60)));
  }

  /** 作業状況の把握に関するexecutedを処理する。 */
  void executed(Instant at) {
    lastExecution = at;
    executions.addLast(at);
  }

  /** 作業状況の把握に関する開始を処理する。 */
  void started(Instant at) {
    last = at;
    key = candidate;
    starts.addLast(at);
    switches.clear();
  }
}
