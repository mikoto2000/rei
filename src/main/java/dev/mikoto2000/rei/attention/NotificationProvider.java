package dev.mikoto2000.rei.attention;

/** Provider boundary beneath the existing durable Inbox/outbox. No provider retries an uncertain send. */
public interface NotificationProvider {
  record Receipt(String status,String reason,String providerReceipt,int retryAfterSeconds) {
    public Receipt {
      if(!java.util.Set.of("SENT","FAILED","UNKNOWN").contains(status)||reason==null||!reason.matches("[a-z0-9_]{1,128}")
          ||providerReceipt==null||!providerReceipt.matches("[A-Za-z0-9:.]{0,128}")||retryAfterSeconds<0||retryAfterSeconds>3600)
        throw new IllegalArgumentException("Invalid notification receipt");
    }
  }
  String name();
  /** Hash of administrator-owned destination identity, never a secret-bearing URL or credential. */
  String destination();
  default long minimumIntervalMillis(){return 0;}
  Receipt deliver(String id,String metadata) throws Exception;
}
