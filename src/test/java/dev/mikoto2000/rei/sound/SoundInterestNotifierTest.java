package dev.mikoto2000.rei.sound;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.interest.ConsoleInterestNotifier;
import dev.mikoto2000.rei.interest.InterestUpdate;

class SoundInterestNotifierTest {

  @Test
  void notifyUpdateCallsSoundNotificationServiceWithTopicAndSummary() {
    SoundNotificationService soundService = mock(SoundNotificationService.class);
    ConsoleInterestNotifier consoleNotifier = mock(ConsoleInterestNotifier.class);
    SoundInterestNotifier notifier = new SoundInterestNotifier(soundService, consoleNotifier);

    InterestUpdate update = new InterestUpdate(
        1L, "Neovim", "reason", "query", "最新の Neovim 情報",
        List.of("https://example.com"), OffsetDateTime.now(ZoneOffset.UTC));

    notifier.notifyUpdate(update);

    verify(soundService).notify("Neovim 最新の Neovim 情報");
  }

  @Test
  void notifyUpdateAlsoDelegatesToConsoleNotifier() {
    SoundNotificationService soundService = mock(SoundNotificationService.class);
    ConsoleInterestNotifier consoleNotifier = mock(ConsoleInterestNotifier.class);
    SoundInterestNotifier notifier = new SoundInterestNotifier(soundService, consoleNotifier);

    InterestUpdate update = new InterestUpdate(
        1L, "Neovim", "reason", "query", "最新の Neovim 情報",
        List.of("https://example.com"), OffsetDateTime.now(ZoneOffset.UTC));

    notifier.notifyUpdate(update);

    verify(consoleNotifier).notifyUpdate(eq(update));
  }
}
