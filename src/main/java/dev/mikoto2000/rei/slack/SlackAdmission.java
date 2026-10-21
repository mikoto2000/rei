package dev.mikoto2000.rei.slack;

/** Slackとの連携に必要なSlack受付を扱う。 */
public final class SlackAdmission {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private SlackAdmission() {}

  /** 要求を後続の処理へ引き渡すため、Slack受付を受け付ける。 */
  public static boolean accept(
      SlackProperties p, String self, SlackMessage m, boolean knownThread) {
    if (m == null
        || self == null
        || self.isBlank()
        || m.eventId() == null
        || m.ts() == null
        || m.text() == null
        || m.text().isBlank()
        || m.text().length() > 16384
        || m.user() == null
        || self.equals(m.user())
        || m.botId() != null && !m.botId().isBlank()
        || m.subtype() != null && !m.subtype().isBlank()
        || !("message".equals(m.type()) || "app_mention".equals(m.type()))) return false;
    if (p.getAllowedWorkspaceIds() == null
        || !p.getAllowedWorkspaceIds().contains(m.team())
        || p.getAllowedUserIds() == null
        || !p.getAllowedUserIds().contains(m.user())
        || p.getAllowedChannelIds() == null
        || !p.getAllowedChannelIds().contains(m.channel())) return false;
    if ("message".equals(m.type())
        && !java.util.Set.of("im", "channel", "group")
            .contains(m.channelType() == null ? "" : m.channelType())) return false;
    if (knownThread) return true;
    if (m.dm())
      return m.threadTs() == null || m.threadTs().isBlank() || m.threadTs().equals(m.ts());
    return m.text().contains("<@" + self + ">");
  }
}
