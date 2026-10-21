package dev.mikoto2000.rei.slack;

/**
 * Slackとの連携で受け渡すSlackメッセージを表す。
 *
 * <p>Only normalized human message fields enter the durable inbox.
 *
 * @param eventId イベントの識別子
 * @param team チーム
 * @param channel チャネル
 * @param ts 記録時刻
 * @param threadTs スレッド記録時刻
 * @param user 利用者
 * @param botId 応答担当識別子
 * @param subtype 詳細な種別
 * @param type 種別
 * @param channelType チャネル種別
 * @param text テキスト
 */
public record SlackMessage(
    String eventId,
    String team,
    String channel,
    String ts,
    String threadTs,
    String user,
    String botId,
    String subtype,
    String type,
    String channelType,
    String text) {
  /** Slackとの連携で用いるスレッドを返す。 */
  public String thread() {
    return threadTs == null || threadTs.isBlank() ? ts : threadTs;
  }

  /** 対象を照合・参照するためのキーを返す。 */
  public String key() {
    return team + ":" + channel + ":" + ts;
  }

  /** Slackとの連携において、dmに該当するか判定する。 */
  public boolean dm() {
    return "im".equals(channelType);
  }
}
