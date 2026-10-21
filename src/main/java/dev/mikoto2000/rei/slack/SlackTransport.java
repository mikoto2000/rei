package dev.mikoto2000.rei.slack;

/**
 * Slackとの連携に必要なSlack通信経路を扱う。
 *
 * <p>Transport outcomes separate known rejection from uncertain external effects.
 */
@FunctionalInterface
public interface SlackTransport {
  /** Slackとの連携で用いる送信を返す。 */
  Delivery send(SlackStore.Outgoing row);

  /**
   * Slackとの連携で受け渡す配信を表す。
   *
   * @param state 状態
   * @param ts 記録時刻
   * @param retryAfterSeconds 再試行までの待ち時間秒数
   */
  record Delivery(String state, String ts, int retryAfterSeconds) {
    /** Slackとの連携で用いる送信を返す。 */
    public static Delivery sent(String ts) {
      return new Delivery("SENT", ts, 0);
    }

    /** Slackとの連携で用いる再試行を返す。 */
    public static Delivery retry(int seconds) {
      return new Delivery("RETRY", null, Math.max(1, seconds));
    }

    /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
    public static Delivery failed() {
      return new Delivery("FAILED", null, 0);
    }

    /** 確定できない結果を、判定不能の情報として表す。 */
    public static Delivery unknown() {
      return new Delivery("UNKNOWN", null, 0);
    }
  }
}
