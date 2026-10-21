package dev.mikoto2000.rei.core.contextbudget;

import java.util.LinkedHashMap;
import java.util.function.LongSupplier;

/**
 * モデルへ渡す文脈の管理に必要な圧縮再試行利用条件の確認を扱う。
 *
 * <p>Bounded local failure state. It contains settings and limits, never conversation text.
 */
final class CompressionRetryGuard {
  /**
   * モデルへ渡す文脈の管理で受け渡す判断結果を表す。
   *
   * @param allowed 許可
   * @param announce 利用者への案内
   * @param waitSeconds 待機秒数
   * @param lastReason 直近理由
   */
  record Decision(boolean allowed, boolean announce, long waitSeconds, String lastReason) {}

  /** 許容件数。 */
  private static final int CAPACITY = 256;

  /** モデルへ渡す文脈の管理に必要な失敗情報を扱う。 */
  private static final class Failure {
    /** 機能の構成／理由。 */
    String configuration, reason;

    /** 失敗一覧。 */
    int failures;

    /** 再試行日時／入力上限。 */
    long retryAt, inputLimit = Long.MAX_VALUE;

    /** 終了の案内を通知済みかどうか。 */
    boolean announced;
  }

  /** 失敗一覧。 */
  private final LinkedHashMap<String, Failure> failures = new LinkedHashMap<>(16, .75f, true);

  /** ミリ秒数。 */
  private final LongSupplier milliseconds;

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  CompressionRetryGuard() {
    this(() -> System.nanoTime() / 1_000_000);
  }

  /** 指定された値や利用先を使って、CompressionRetryGuardを初期化する。 */
  CompressionRetryGuard(LongSupplier milliseconds) {
    this.milliseconds = milliseconds;
  }

  /** モデルへ渡す文脈の管理で用いる状態を返す。 */
  private Failure state(String key, String configuration) {
    var failure = failures.get(key);
    if (failure != null && !failure.configuration.equals(configuration)) {
      failures.remove(key);
      return null;
    }
    return failure;
  }

  /** モデルへ渡す文脈の管理で用いる入力上限を返す。 */
  synchronized long inputLimit(String key, String configuration, long standard) {
    var failure = state(key, configuration);
    return failure == null ? standard : Math.min(standard, failure.inputLimit);
  }

  /** モデルへ渡す文脈の管理で用いる判断結果を返す。 */
  synchronized Decision decision(String key, String configuration) {
    var failure = state(key, configuration);
    if (failure == null || milliseconds.getAsLong() >= failure.retryAt)
      return new Decision(true, false, 0, null);
    boolean announce = !failure.announced;
    failure.announced = true;
    return new Decision(
        false,
        announce,
        Math.max(1, (failure.retryAt - milliseconds.getAsLong() + 999) / 1000),
        failure.reason);
  }

  /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
  synchronized long failed(
      String key,
      String configuration,
      String reason,
      long before,
      int summaryTokens,
      int cooldownSeconds,
      boolean shrink) {
    var failure = state(key, configuration);
    if (failure == null) {
      failure = new Failure();
      failure.configuration = configuration;
      failures.put(key, failure);
    }
    failure.failures = Math.min(4, failure.failures + 1);
    failure.reason = reason;
    failure.announced = false;
    long wait = (long) cooldownSeconds * (1L << (failure.failures - 1));
    failure.retryAt = milliseconds.getAsLong() + wait * 1000;
    if (shrink)
      failure.inputLimit = Math.min(failure.inputLimit, Math.max(2L * summaryTokens, before / 2));
    while (failures.size() > CAPACITY) failures.remove(failures.keySet().iterator().next());
    return wait;
  }

  /** モデルへ渡す文脈の管理に関する成功を処理する。 */
  synchronized void succeeded(String key) {
    failures.remove(key);
  }
}
