package dev.mikoto2000.rei.http;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 外部情報の取得で受け渡す取得操作を表す。
 *
 * <p>A single deadline includes DNS, redirects, transfer and decoding.
 *
 * @param cancellationCheck キャンセル確認
 * @param deadlineNanos 期限ナノ秒数
 */
public record FetchOperation(Runnable cancellationCheck, long deadlineNanos) {
  /** 外部情報の取得で用いる活動中を返す。 */
  public static FetchOperation active() {
    return new FetchOperation(() -> {}, Long.MAX_VALUE);
  }

  /** 指定されたタイムアウトを反映した情報を作成する。 */
  public FetchOperation withTimeout(Duration timeout) {
    if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout");
    long now = System.nanoTime(), delta = timeout.toNanos();
    long end = now > Long.MAX_VALUE - delta ? Long.MAX_VALUE : now + delta;
    return new FetchOperation(cancellationCheck, Math.min(deadlineNanos, end));
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public void check() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    cancellationCheck.run();
    if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos)
      throw new HttpFetchException(HttpFetchException.Code.TOTAL_TIMEOUT);
  }

  /** 外部情報の取得で用いる残量を返す。 */
  public Duration remaining() {
    check();
    return Duration.ofNanos(
        deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime());
  }

  /** 必要な状態が整ってから先へ進むため、取得操作を待つ。 */
  public <T> T await(CompletableFuture<T> future) {
    try {
      for (; ; ) {
        check();
        try {
          T result =
              future.get(
                  Math.max(1, Math.min(100_000_000L, remaining().toNanos())), TimeUnit.NANOSECONDS);
          check();
          return result;
        } catch (TimeoutException pending) {
          check();
        }
      }
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException();
    } catch (ExecutionException failed) {
      throw classify(failed.getCause());
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  /** 外部情報の取得に関するpropagateControlsを処理する。 */
  public static void propagateControls(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ExecutionStoppedException stopped) throw stopped;
      if (cause instanceof CancellationException cancelled) throw cancelled;
      if (cause instanceof InterruptedException) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        throw new CancellationException();
      }
    }
  }

  /** Safe fixed diagnostic code without exposing transport exception types to callers. */
  public static String diagnosticCode(Throwable failure, String fallback) {
    return failure instanceof HttpFetchException http ? http.code().name() : fallback;
  }

  /** 得られた情報を分類し、後続の判断で扱い分けられるようにする。 */
  public static RuntimeException classify(Throwable failure) {
    propagateControls(failure);
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof HttpFetchException safe) return safe;
      if (cause instanceof io.netty.channel.ConnectTimeoutException)
        return new HttpFetchException(HttpFetchException.Code.CONNECT_TIMEOUT);
      if (cause instanceof io.netty.handler.timeout.ReadTimeoutException)
        return new HttpFetchException(HttpFetchException.Code.READ_TIMEOUT);
      if (cause instanceof TimeoutException)
        return new HttpFetchException(HttpFetchException.Code.TOTAL_TIMEOUT);
    }
    return new HttpFetchException(HttpFetchException.Code.NETWORK_ERROR);
  }
}
