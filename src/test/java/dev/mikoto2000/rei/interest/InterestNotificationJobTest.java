package dev.mikoto2000.rei.interest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class InterestNotificationJobTest {

  @Test
  void runDoesNothingWhenNotificationIsDisabled() {
    InterestDiscoveryJob discoveryJob = org.mockito.Mockito.mock(InterestDiscoveryJob.class);
    InterestNotifier notifier = org.mockito.Mockito.mock(InterestNotifier.class);
    InterestProperties properties = new InterestProperties();
    properties.setNotificationEnabled(false);
    InterestNotificationJob job = new InterestNotificationJob(discoveryJob, notifier, properties);

    job.run();

    verify(discoveryJob, never()).discoverNow(any());
    verify(notifier, never()).notifyUpdate(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void runNotifiesSavedUpdatesWhenNotificationIsEnabled() {
    InterestDiscoveryJob discoveryJob = org.mockito.Mockito.mock(InterestDiscoveryJob.class);
    InterestNotifier notifier = org.mockito.Mockito.mock(InterestNotifier.class);
    InterestProperties properties = new InterestProperties();
    properties.setNotificationEnabled(true);
    InterestNotificationJob job = new InterestNotificationJob(discoveryJob, notifier, properties);
    InterestUpdate update = new InterestUpdate(
        1L,
        "Neovim 開発環境",
        "繰り返し話題になっている",
        "Neovim devcontainer best practices",
        "Neovim docs / container tips",
        List.of("https://example.com/nvim"),
        OffsetDateTime.of(2026, 4, 28, 0, 0, 0, 0, ZoneOffset.UTC));

    when(discoveryJob.discoverNow(any())).thenReturn(List.of(update));

    job.run();

    verify(discoveryJob).discoverNow(any());
    verify(notifier).notifyUpdate(update);
  }
}
