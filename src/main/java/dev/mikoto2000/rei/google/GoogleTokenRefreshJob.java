package dev.mikoto2000.rei.google;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@ConditionalOnProperty(prefix = "rei.google.token-refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoogleTokenRefreshJob {
  private final GoogleOAuthService service;
  private final Duration advance;

  public GoogleTokenRefreshJob(GoogleOAuthService service,
      @Value("${rei.google.token-refresh.advance:5m}") String advance) {
    this.advance = DurationStyle.detectAndParse(advance);
    if (this.advance.isNegative() || this.advance.isZero()) {
      throw new IllegalArgumentException("Google token refresh advance must be positive");
    }
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${rei.google.token-refresh.check-interval:5m}")
  public void run() {
    try {
      if (service.refreshIfExpiring(advance)) {
        log.debug("Google access token refreshed");
      }
    } catch (Exception e) {
      // OAuth exception bodies can contain credentials; do not log their contents.
      log.warn("Google token refresh failed ({}); will retry on the next check. If this persists, run schedule auth or task auth.",
          e.getClass().getSimpleName());
    }
  }
}
