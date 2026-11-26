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

  /** Local reasons only; never includes a provider body, credential, or native request. */
  enum Invalidation {
    STOPPED,
    SIGNED_OUT,
    REGISTRATION_CHANGED,
    AUTHENTICATION_INVALID,
    CONSENT_REQUIRED
  }

  /** One inference admission; release it before tools or a child request run. */
  interface Request extends AutoCloseable {
    /** Completes when this admission is invalidated; contains no credential or native options. */
    java.util.concurrent.CompletionStage<Void> invalidated();

    /** Releases the request exactly once, including on error and cancellation. */
    @Override
    void close();
  }

  /** Safe closed default until an account implementation supplies request admission. */
  default Request begin(String account, String registration, String model) {
    throw new IllegalStateException("ChatGPT inference admission is unavailable");
  }
}
