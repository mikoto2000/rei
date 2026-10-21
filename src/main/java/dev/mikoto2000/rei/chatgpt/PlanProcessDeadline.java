package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * ChatGPTとの連携に必要な計画プロセス期限を扱う。
 *
 * <p>The caller's remaining deadline includes startup; process cleanup gets no new model budget.
 */
final class PlanProcessDeadline {
  /** nano時刻の取得元。 */
  private final LongSupplier nanoClock;

  /** 期限。 */
  private final long deadline;

  /** ChatGPTとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
  PlanProcessDeadline(Duration timeout) {
    this(timeout, System::nanoTime);
  }

  /** 指定された値や利用先を使って、PlanProcessDeadlineを初期化する。 */
  PlanProcessDeadline(Duration timeout, LongSupplier nanoClock) {
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(PlanExecutionProperties.MAX_TIMEOUT) > 0)
      throw new IllegalArgumentException(
          "Plan process timeout must be positive and at most 30 minutes");
    this.nanoClock = nanoClock;
    deadline = nanoClock.getAsLong() + timeout.toNanos();
  }

  /** ChatGPTとの連携で用いる残量を返す。 */
  Duration remaining() {
    long nanos = deadline - nanoClock.getAsLong();
    if (nanos <= 0) throw ChatGptException.of(TIMEOUT);
    return Duration.ofNanos(nanos);
  }

  /** ChatGPTとの連携で用いるwslWatchdog継続時間を返す。 */
  String wslWatchdogDuration() {
    // The RPC enforces the exact remaining deadline. Only its TERM/KILL cleanup may take longer.
    long nanos = remaining().toNanos();
    return (Math.floorDiv(nanos - 1, 1_000_000_000L) + 1 + 5) + "s";
  }

  /** ChatGPTとの連携で用いる利用可否の確認待機ミリ秒数を返す。 */
  long probeWaitMillis() {
    return Math.min(10000, Math.max(1, remaining().toMillis()));
  }
}
