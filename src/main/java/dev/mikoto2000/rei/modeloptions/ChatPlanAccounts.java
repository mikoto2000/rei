package dev.mikoto2000.rei.modeloptions;

/**
 * Account access for common chat connections; protocol and protected storage stay with the owner.
 */
public interface ChatPlanAccounts {
  /** Local authorization incarnation, or null; never refreshes or returns credentials. */
  String registration(String account);

  /**
   * Late credential acquisition for this exact registration and available model; never persist it.
   */
  String credential(String account, String registration, String model);

  /** Account-specific visible model IDs in server order, bound to this registration. */
  java.util.List<String> models(String account, String registration);
}
