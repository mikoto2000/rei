package dev.mikoto2000.rei.http;

import java.time.Duration;
import java.util.concurrent.*;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;

/** A single deadline includes DNS, redirects, transfer and decoding. */
public record FetchOperation(Runnable cancellationCheck, long deadlineNanos) {
  public static FetchOperation active() { return new FetchOperation(() -> {}, Long.MAX_VALUE); }
  public FetchOperation withTimeout(Duration timeout) {
    if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout");
    long now = System.nanoTime(), delta = timeout.toNanos();
    long end = now > Long.MAX_VALUE - delta ? Long.MAX_VALUE : now + delta;
    return new FetchOperation(cancellationCheck, Math.min(deadlineNanos, end));
  }
  public void check() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    cancellationCheck.run();
    if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos)
      throw new HttpFetchException(HttpFetchException.Code.TOTAL_TIMEOUT);
  }
  public Duration remaining() {
    check();
    return Duration.ofNanos(deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime());
  }
  public <T> T await(CompletableFuture<T> future) {
    try {
      for (;;) {
        check();
        try {
          T result = future.get(Math.max(1, Math.min(100_000_000L, remaining().toNanos())), TimeUnit.NANOSECONDS);
          check(); return result;
        } catch (TimeoutException pending) { check(); }
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw new CancellationException();
    } catch (ExecutionException failed) {
      throw classify(failed.getCause());
    } finally { if (!future.isDone()) future.cancel(true); }
  }
  public static void propagateControls(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ExecutionStoppedException stopped) throw stopped;
      if (cause instanceof CancellationException cancelled) throw cancelled;
      if (cause instanceof InterruptedException) {
        Thread.currentThread().interrupt(); throw new CancellationException();
      }
    }
  }
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
