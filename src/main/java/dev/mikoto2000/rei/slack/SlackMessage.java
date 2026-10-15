package dev.mikoto2000.rei.slack;

/** Only normalized human message fields enter the durable inbox. */
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
  public String thread() {
    return threadTs == null || threadTs.isBlank() ? ts : threadTs;
  }

  public String key() {
    return team + ":" + channel + ":" + ts;
  }

  public boolean dm() {
    return "im".equals(channelType);
  }
}
