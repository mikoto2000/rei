package dev.mikoto2000.rei.attention;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 注意の管理で受け渡すSlack通知設定を表す。
 *
 * @param enabled 有効状態
 * @param endpoint 接続先
 * @param botToken 応答担当トークン
 * @param channel チャネル
 * @param channels チャネル一覧
 */
@ConfigurationProperties("rei.attention.slack")
public record SlackNotificationProperties(
    boolean enabled, String endpoint, String botToken, String channel, Set<String> channels) {
  /** 受け取った情報からSlackNotificationPropertiesを作成する。 */
  public SlackNotificationProperties {
    endpoint =
        endpoint == null || endpoint.isBlank()
            ? "https://slack.com/api/chat.postMessage"
            : endpoint;
    channels = channels == null ? Set.of() : Set.copyOf(channels);
    if (channels.size() > 64
        || channels.stream().anyMatch(value -> !value.matches("[CGD][A-Z0-9]{5,31}")))
      throw new IllegalArgumentException("Invalid Slack channel allowlist");
    if (botToken != null
        && (botToken.length() > 4096
            || botToken.chars().anyMatch(value -> value <= 32 || value >= 127)))
      throw new IllegalArgumentException("Invalid Slack credential");
    if (enabled) {
      if (channel == null || !channels.contains(channel) || botToken == null || botToken.isBlank())
        throw new IllegalArgumentException("Slack requires credential and allowlisted channel");
      try {
        var uri = URI.create(endpoint);
        boolean official = endpoint.equals("https://slack.com/api/chat.postMessage");
        boolean fixture =
            "http".equals(uri.getScheme())
                && Set.of("127.0.0.1", "[::1]").contains(uri.getHost())
                && "/api/chat.postMessage".equals(uri.getRawPath())
                && uri.getPort() > 0
                && uri.getPort() <= 65535;
        if (endpoint.length() > 2048
            || !(official || fixture)
            || uri.getRawUserInfo() != null
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null) throw new IllegalArgumentException();
      } catch (RuntimeException error) {
        throw new IllegalArgumentException(
            "Slack endpoint must be the official API or numeric-loopback fixture");
      }
    }
  }

  /** 注意の管理の情報を、表示や診断で確認できる文字列表現にする。 */
  @Override
  public String toString() {
    return "SlackNotificationProperties[enabled="
        + enabled
        + ", credential=<redacted>, destination=<configured>, allowlistCount="
        + channels.size()
        + "]";
  }
}
