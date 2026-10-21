package dev.mikoto2000.rei.attention;

/**
 * 通知を用途や設定に応じて提供する窓口。
 *
 * <p>Provider boundary beneath the existing durable Inbox/outbox. No provider retries an uncertain
 * send.
 */
public interface NotificationProvider {
  /**
   * 注意の管理で受け渡す受付記録を表す。
   *
   * @param status 状態
   * @param reason 理由
   * @param providerReceipt 提供元受付記録
   * @param retryAfterSeconds 再試行までの待ち時間秒数
   */
  record Receipt(String status, String reason, String providerReceipt, int retryAfterSeconds) {
    /** 受け取った情報からReceiptを作成する。 */
    public Receipt {
      if (!java.util.Set.of("SENT", "FAILED", "UNKNOWN").contains(status)
          || reason == null
          || !reason.matches("[a-z0-9_]{1,128}")
          || providerReceipt == null
          || !providerReceipt.matches("[A-Za-z0-9:.]{0,128}")
          || retryAfterSeconds < 0
          || retryAfterSeconds > 3600)
        throw new IllegalArgumentException("Invalid notification receipt");
    }
  }

  /** 注意の管理で用いる名前を返す。 */
  String name();

  /**
   * 注意の管理で用いる配信先を返す。
   *
   * <p>Hash of administrator-owned destination identity, never a secret-bearing URL or credential.
   */
  String destination();

  /** 注意の管理で用いる最小実行間隔ミリ秒数を返す。 */
  default long minimumIntervalMillis() {
    return 0;
  }

  /** 注意の管理で用いるdeliverを返す。 */
  Receipt deliver(String id, String metadata) throws Exception;
}
