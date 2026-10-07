package dev.mikoto2000.rei.attention;

/** Compatibility adapter for the existing webhook transport and receipt semantics. */
public final class WebhookNotificationProvider implements NotificationProvider {
  private final AttentionDeliveryProperties properties;private final JdkAttentionSender sender;
  public WebhookNotificationProvider(AttentionDeliveryProperties properties,JdkAttentionSender sender){this.properties=properties;this.sender=sender;}
  public String name(){return "WEBHOOK";}
  public String destination(){return SlackNotificationProvider.digest(properties.endpoint());}
  public Receipt deliver(String id,String metadata) throws Exception {int code=sender.send(properties,id,metadata);return new Receipt(code>=200&&code<300?"SENT":"FAILED","http_"+code,"",0);}
}
