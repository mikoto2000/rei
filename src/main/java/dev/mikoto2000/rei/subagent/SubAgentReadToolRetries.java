package dev.mikoto2000.rei.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 補助エージェントへの委譲に必要な補助エージェント読み取りツール再試行回数を扱う。
 *
 * <p>Narrow transient read failures only. Returned error strings and model/provider failures are
 * not replayed.
 */
final class SubAgentReadToolRetries {
  /** 残量／試行回数。 */
  private final AtomicInteger remaining, attempts = new AtomicInteger();

  /** 失敗一覧。 */
  private final List<String> failures = new ArrayList<>();

  /** 指定された値や利用先を使って、SubAgentReadToolRetriesを初期化する。 */
  SubAgentReadToolRetries(int max) {
    if (max < 0 || max > 3) throw new IllegalArgumentException("Read retries must be 0 to 3");
    remaining = new AtomicInteger(max);
  }

  /** 補助エージェントへの委譲で用いる試行回数を返す。 */
  int attempts() {
    return attempts.get();
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  synchronized List<String> history() {
    return List.copyOf(failures);
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  private synchronized void failure() {
    if (failures.size() < 4) failures.add("TRANSIENT_READ_TOOL_FAILURE");
  }

  /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
  String call(
      Supplier<String> invoke, Runnable authorize, Runnable check, BooleanSupplier eligible) {
    boolean retrying = false;
    while (true) {
      check.run();
      authorize.run();
      if (retrying) {
        if (!eligible.getAsBoolean())
          throw new IllegalStateException("Read Tool retry is no longer permitted");
        attempts.incrementAndGet();
      }
      try {
        return invoke.get();
      } catch (RuntimeException error) {
        if (!transientFailure(error) || !eligible.getAsBoolean()) throw error;
        failure();
        check.run();
        if (remaining.getAndUpdate(value -> Math.max(0, value - 1)) <= 0) throw error;
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
          Thread.currentThread().interrupt();
          throw new java.util.concurrent.CancellationException("Read retry cancelled");
        }
        retrying = true;
      }
    }
  }

  /** 補助エージェントへの委譲において、一時的な失敗情報に該当するか判定する。 */
  private static boolean transientFailure(RuntimeException error) {
    if (error instanceof org.springframework.dao.TransientDataAccessException) return true;
    if (!(error instanceof java.io.UncheckedIOException
        || error instanceof org.springframework.web.client.ResourceAccessException)) return false;
    Throwable cause = error.getCause();
    for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
      if (cause instanceof java.net.ConnectException
          || cause instanceof java.net.SocketTimeoutException
          || cause instanceof java.net.http.HttpTimeoutException) return true;
    }
    return false;
  }
}
