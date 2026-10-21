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
public class GoogleTokenRefreshJob {
  /** サービス。 */
  private final GoogleOAuthService service;

  /** 次の確認までに進める時間。 */
  private final Duration advance;

  /** 指定された値や利用先を使って、GoogleTokenRefreshJobを初期化する。 */
  public GoogleTokenRefreshJob(
      GoogleOAuthService service, @Value("${rei.google.token-refresh.advance:5m}") String advance) {
    this.advance = DurationStyle.detectAndParse(advance);
    if (this.advance.isNegative() || this.advance.isZero()) {
      throw new IllegalArgumentException("Google token refresh advance must be positive");
    }
    this.service = service;
  }

  /** Googleサービスの認証の操作を実行する。 */
  @Scheduled(fixedDelayString = "${rei.google.token-refresh.check-interval:5m}")
  public void run() {
    try {
      if (service.refreshIfExpiring(advance)) {
        log.debug("Google access token refreshed");
      }
    } catch (Exception e) {
      // OAuth exception bodies can contain credentials; do not log their contents.
      log.warn(
          "Google token refresh failed ({}); will retry on the next check. If this persists, run schedule auth or task auth.",
          e.getClass().getSimpleName());
    }
  }
}
