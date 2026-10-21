package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.llm.ModelCallBudget;
import java.time.Duration;

/**
 * 長期記憶の保存と検索に必要な自動待機実行を扱う。
 *
 * <p>Per-execution limits, passed through the existing model accounting path.
 */
public final class AutoSleepExecution implements ModelCallBudget {
  /** 期限一覧。 */
  private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
          r -> {
            var t = new Thread(r, "rei-auto-sleep-deadline");
            t.setDaemon(true);
            return t;
          });

  /** タイムアウト／合計。 */
  private final Duration timeout, total;

  /** 経過時間を測る時刻の取得元。 */
  private final java.util.function.LongSupplier ticker;

  /** 開始。 */
  private final long started;

  /** 委譲先。 */
  private ModelCallBudget delegate;

  /** 期限切れ。 */
  private volatile boolean expired;

  /** 進捗保存済み。 */
  private boolean progressSaved;

  /** 長期記憶の保存と検索に関する進捗保存済みを処理する。 */
  public void progressSaved() {
    progressSaved = true;
  }

  /** 長期記憶の保存と検索において、保存済み進捗の有無を判定する。 */
  public boolean hasSavedProgress() {
    return progressSaved;
  }

  /** 長期記憶の保存と検索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AutoSleepExecution(Duration timeout, Duration total) {
    this(timeout, total, System::nanoTime);
  }

  /** 指定された値や利用先を使って、AutoSleepExecutionを初期化する。 */
  public AutoSleepExecution(
      Duration timeout, Duration total, java.util.function.LongSupplier ticker) {
    this.timeout = timeout;
    this.total = total;
    this.ticker = ticker;
    started = ticker.getAsLong();
  }

  /** 長期記憶の保存と検索で用いるaccountingを返す。 */
  public AutoSleepExecution accounting(ModelCallBudget delegate) {
    this.delegate = delegate;
    return this;
  }

  /** 長期記憶の保存と検索で用いる残量を返す。 */
  public Duration remaining() {
    var remaining = total.minusNanos(ticker.getAsLong() - started);
    if (remaining.isNegative() || remaining.isZero())
      throw new Failure("AUTO_SLEEP_DEADLINE_EXCEEDED", null);
    return remaining;
  }

  /**
   * 長期記憶の保存と検索で受け渡す待機を表す。
   *
   * @param duration 継続時間
   * @param deadline 期限
   */
  public record Wait(Duration duration, boolean deadline) {}

  /** 必要な状態が整ってから先へ進むため、上限を待つ。 */
  public Wait waitLimit() {
    var left = remaining();
    return new Wait(left.compareTo(timeout) <= 0 ? left : timeout, left.compareTo(timeout) <= 0);
  }

  /** 必要な状態が整ってから先へ進むため、時刻を待つ。 */
  public Duration waitTime() {
    return waitLimit().duration();
  }

  /** 長期記憶の保存と検索の操作を実行する。 */
  public void run() {
    remaining();
    if (delegate != null) delegate.run();
  }

  /** トークン数による実行予算の制限が有効かどうかを返す。 */
  public boolean tokenLimitEnabled() {
    return delegate != null && delegate.tokenLimitEnabled();
  }

  /** 使用したトークン数を実行予算へ記録する。 */
  public void recordTotalTokens(Integer tokens) {
    if (delegate != null) delegate.recordTotalTokens(tokens);
  }

  /**
   * 長期記憶の保存と検索で用いるwatchdogを返す。
   *
   * <p>Bound non-stream work too (including Episode indexing), using existing interruption.
   */
  public Deadline watchdog() {
    return new Deadline();
  }

  /** 長期記憶の保存と検索において、期限期限切れに該当するか判定する。 */
  public boolean deadlineExpired() {
    return expired;
  }

  /** 長期記憶の保存と検索に必要な期限を扱う。 */
  public final class Deadline implements AutoCloseable {
    /** 活動中。 */
    private boolean active = true;

    /** スレッド。 */
    private final Thread thread = Thread.currentThread();

    /** 予約した時刻の到来を通知する処理。 */
    private final java.util.concurrent.ScheduledFuture<?> alarm =
        DEADLINES.schedule(
            () -> {
              synchronized (this) {
                if (active) {
                  expired = true;
                  thread.interrupt();
                }
              }
            },
            remaining().toNanos(),
            java.util.concurrent.TimeUnit.NANOSECONDS);

    /** 長期記憶の保存と検索で利用した資源や登録を解放し、利用を終了する。 */
    public synchronized void close() {
      active = false;
      alarm.cancel(false);
    }
  }

  /** 長期記憶の保存と検索に必要な失敗情報を扱う。 */
  public static final class Failure extends IllegalStateException {
    /** コード。 */
    public final String code;

    /** 指定された値や利用先を使って、Failureを初期化する。 */
    public Failure(String code, Throwable cause) {
      super(code, cause);
      this.code = code;
    }
  }
}
