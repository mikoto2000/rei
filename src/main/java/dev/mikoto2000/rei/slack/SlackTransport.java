package dev.mikoto2000.rei.slack;

/** Transport outcomes separate known rejection from uncertain external effects. */
@FunctionalInterface
public interface SlackTransport {
  Delivery send(SlackStore.Outgoing row);
  record Delivery(String state,String ts,int retryAfterSeconds) {
    public static Delivery sent(String ts){return new Delivery("SENT",ts,0);}
    public static Delivery retry(int seconds){return new Delivery("RETRY",null,Math.max(1,seconds));}
    public static Delivery failed(){return new Delivery("FAILED",null,0);}
    public static Delivery unknown(){return new Delivery("UNKNOWN",null,0);}
  }
}
