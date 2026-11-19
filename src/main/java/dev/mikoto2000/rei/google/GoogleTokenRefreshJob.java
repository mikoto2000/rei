package dev.mikoto2000.rei.google;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Googleサービスの認証に必要なGoogleトークン再確認実行する作業を扱う。 */
@Component
@Slf4j
@ConditionalOnProperty(
    prefix = "rei.google.token-refresh",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public final class GoogleTokenRefreshJob {
  /** サービス。 */
  @FunctionalInterface
  private interface ExpiringTokenRefresh {
    boolean refresh() throws java.io.IOException, java.security.GeneralSecurityException;
  }

  private final ExpiringTokenRefresh refreshExpiringToken;
  private final java.util.function.BooleanSupplier reauthorizationRequired;
  private final java.time.Clock clock;
  private java.time.Instant retryAt = java.time.Instant.MIN;
  private int failures;

  /** 指定された値や利用先を使って、GoogleTokenRefreshJobを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public GoogleTokenRefreshJob(
      GoogleOAuthService service, @Value("${rei.google.token-refresh.advance:5m}") String advance) {
    this(service, advance, java.time.Clock.systemUTC());
  }

  GoogleTokenRefreshJob(GoogleOAuthService service, String advance, java.time.Clock clock) {
    this.clock = clock;
    this.reauthorizationRequired = service::reauthorizationRequired;
    Duration advanceBy = DurationStyle.detectAndParse(advance);
    if (advanceBy.isNegative() || advanceBy.isZero()) {
      throw new IllegalArgumentException("Google token refresh advance must be positive");
    }
    this.refreshExpiringToken = () -> service.refreshIfExpiring(advanceBy);
  }

  /** Googleサービスの認証の操作を実行する。 */
  @Scheduled(fixedDelayString = "${rei.google.token-refresh.check-interval:5m}")
  public synchronized void run() {
    if (clock.instant().isBefore(retryAt)) return;
    try {
      if (refreshExpiringToken.refresh()) {
        log.debug("Google access token refreshed");
      }
      failures = 0;
      retryAt = java.time.Instant.MIN;
    } catch (java.io.IOException | java.security.GeneralSecurityException | RuntimeException e) {
      // OAuth exception bodies can contain credentials; never log their contents.
      if (reauthorizationRequired.getAsBoolean()) {
        log.warn(
            "Google reauthorization required; automatic refresh suspended. Run schedule auth or task auth.");
      } else {
        failures = Math.min(8, failures + 1);
        long delaySeconds = Math.min(21600L, 300L << (failures - 1));
        retryAt = clock.instant().plusSeconds(delaySeconds);
        log.warn(
            "Google token refresh temporarily failed ({}); retry in at least {} seconds.",
            e.getClass().getSimpleName(),
            delaySeconds);
      }
    }
  }
}
