package dev.mikoto2000.rei.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GoogleTokenRefreshJobTest {
  @Test void continuesAfterFailure() throws Exception {
    GoogleOAuthService service = mock(GoogleOAuthService.class);
    Duration advance = Duration.ofMinutes(5);
    when(service.refreshIfExpiring(advance)).thenThrow(new IOException("offline")).thenReturn(true);
    GoogleTokenRefreshJob job = new GoogleTokenRefreshJob(service, "5m");
    assertDoesNotThrow(job::run);
    assertDoesNotThrow(job::run);
    verify(service, times(2)).refreshIfExpiring(advance);
  }

  @Test void rejectsNonPositiveAdvance() {
    assertThrows(IllegalArgumentException.class,
        () -> new GoogleTokenRefreshJob(mock(GoogleOAuthService.class), "0s"));
  }

  @Test void schedulerBeanUsesDefaultsAndCanBeDisabled() {
    var runner = new ApplicationContextRunner()
        .withBean(GoogleOAuthService.class, () -> mock(GoogleOAuthService.class))
        .withUserConfiguration(GoogleTokenRefreshJob.class);
    runner.run(context -> {
      assertNull(context.getStartupFailure());
      context.getBean(GoogleTokenRefreshJob.class).run();
      verify(context.getBean(GoogleOAuthService.class)).refreshIfExpiring(Duration.ofMinutes(5));
    });
    runner.withPropertyValues("rei.google.token-refresh.enabled=false").run(context -> {
      assertNull(context.getStartupFailure());
      assertTrue(context.getBeansOfType(GoogleTokenRefreshJob.class).isEmpty());
    });
  }
}
