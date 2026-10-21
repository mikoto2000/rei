package dev.mikoto2000.rei.attention;

/**
 * Webhook通知通知を用途や設定に応じて提供する窓口。
 *
 * <p>Compatibility adapter for the existing webhook transport and receipt semantics.
 */
public final class WebhookNotificationProvider implements NotificationProvider {
  /** この機能の動作設定。 */
  private final AttentionDeliveryProperties properties;

  /** 通知の送信先。 */
  private final JdkAttentionSender sender;

  /** 指定された値や利用先を使って、WebhookNotificationProviderを初期化する。 */
  public WebhookNotificationProvider(
      AttentionDeliveryProperties properties, JdkAttentionSender sender) {
    this.properties = properties;
    this.sender = sender;
  }

  /** 注意の管理で用いる名前を返す。 */
  public String name() {
    return "WEBHOOK";
  }

  /** 注意の管理で用いる配信先を返す。 */
  public String destination() {
    return SlackNotificationProvider.digest(properties.endpoint());
  }

  /** 注意の管理で用いるdeliverを返す。 */
  public Receipt deliver(String id, String metadata) throws Exception {
    int code = sender.send(properties, id, metadata);
    return new Receipt(code >= 200 && code < 300 ? "SENT" : "FAILED", "http_" + code, "", 0);
  }
}
