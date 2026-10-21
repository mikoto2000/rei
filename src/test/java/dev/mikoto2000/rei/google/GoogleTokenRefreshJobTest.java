package dev.mikoto2000.rei.google;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Googleトークン再確認実行する作業の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class GoogleTokenRefreshJobTest {
  /** Googleサービスの認証で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void continuesAfterFailure() throws Exception {
    GoogleOAuthService service = mock(GoogleOAuthService.class);
    Duration advance = Duration.ofMinutes(5);
    when(service.refreshIfExpiring(advance)).thenThrow(new IOException("offline")).thenReturn(true);
    GoogleTokenRefreshJob job = new GoogleTokenRefreshJob(service, "5m");
    assertDoesNotThrow(job::run);
    assertDoesNotThrow(job::run);
    verify(service, times(2)).refreshIfExpiring(advance);
  }

  /** Googleサービスの認証で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectsNonPositiveAdvance() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new GoogleTokenRefreshJob(mock(GoogleOAuthService.class), "0s"));
  }

  /** 実行予定の管理機能の構成要素について、既定値を利用することを確認する。 */
  @Test
  void schedulerBeanUsesDefaultsAndCanBeDisabled() {
    var runner =
        new ApplicationContextRunner()
            .withBean(GoogleOAuthService.class, () -> mock(GoogleOAuthService.class))
            .withUserConfiguration(GoogleTokenRefreshJob.class);
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          context.getBean(GoogleTokenRefreshJob.class).run();
          verify(context.getBean(GoogleOAuthService.class))
              .refreshIfExpiring(Duration.ofMinutes(5));
        });
    runner
        .withPropertyValues("rei.google.token-refresh.enabled=false")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBeansOfType(GoogleTokenRefreshJob.class).isEmpty());
            });
  }
}
