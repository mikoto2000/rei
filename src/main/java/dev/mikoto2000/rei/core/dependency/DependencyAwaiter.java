package dev.mikoto2000.rei.core.dependency;

import dev.mikoto2000.rei.temporal.MonotonicTimeSource;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

/**
 * 外部条件の待機に必要な依存条件Awaiterを扱う。
 *
 * <p>Bounded polling independent of filesystem/process/API implementations and wall-clock changes.
 */
public final class DependencyAwaiter {
  /** 最大タイムアウトの秒数。 */
  public static final int MAX_TIMEOUT_SECONDS = 60;

  /** 外部条件の待機に必要な待機の実行先を扱う。 */
  @FunctionalInterface
  public interface Sleeper {
    /** 外部条件の待機に関する待機を処理する。 */
    void sleep(Duration duration) throws InterruptedException;
  }

  /** 時刻。 */
  private final MonotonicTimeSource time;

  /** 待機の実行先。 */
  private final Sleeper sleeper;

  /** 状態確認実行間隔。 */
  private final Duration pollInterval;

  /** 外部条件の待機を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DependencyAwaiter(MonotonicTimeSource time, Sleeper sleeper) {
    this(time, sleeper, Duration.ofMillis(200));
  }

  /** 指定された値や利用先を使って、DependencyAwaiterを初期化する。 */
  public DependencyAwaiter(MonotonicTimeSource time, Sleeper sleeper, Duration pollInterval) {
    if (pollInterval == null
        || pollInterval.compareTo(Duration.ofMillis(100)) < 0
        || pollInterval.compareTo(Duration.ofSeconds(5)) > 0)
      throw new IllegalArgumentException("Poll interval must be 100ms..5s");
    this.time = time;
    this.sleeper = sleeper;
    this.pollInterval = pollInterval;
  }

  /** 必要な状態が整ってから先へ進むため、依存条件Awaiterを待つ。 */
  public DependencyObservation await(
      Supplier<DependencyObservation> probe, Duration timeout, Runnable check) {
    if (timeout == null
        || timeout.isNegative()
        || timeout.compareTo(Duration.ofSeconds(MAX_TIMEOUT_SECONDS)) > 0)
      throw new IllegalArgumentException(
          "Dependency timeout must be between 0 and " + MAX_TIMEOUT_SECONDS + " seconds");
    long started = time.nanoTime(), limit = timeout.toNanos();
    while (true) {
      check.run();
      if (Thread.currentThread().isInterrupted())
        throw new CancellationException("Dependency wait cancelled");
      var observation = probe.get();
      check.run();
      if (Thread.currentThread().isInterrupted())
        throw new CancellationException("Dependency wait cancelled");
      if (observation.state() != DependencyState.RUNNING
          && observation.state() != DependencyState.WAITING) return observation;
      long remaining = limit - (time.nanoTime() - started);
      if (remaining <= 0)
        return new DependencyObservation(
            observation.id(), DependencyState.WAITING, observation.detail());
      try {
        sleeper.sleep(Duration.ofNanos(Math.min(remaining, pollInterval.toNanos())));
      } catch (InterruptedException error) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        throw new CancellationException("Dependency wait cancelled");
      }
    }
  }
}
