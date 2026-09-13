package dev.mikoto2000.rei.feed.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.feed.FeedSummaryService;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;

class FeedSummaryNarrationTest {

  @Test
  void summaryIsSanitizedAndNarratedWithoutDuplicateCompletionNotification() {
    var service = mock(FeedSummaryService.class);
    var sound = mock(SoundNotificationService.class);
    var narrator = new ChatResponseNarrator(sound);
    when(service.summarizeBriefing()).thenReturn("## 新着記事\n**注目記事** [詳細](https://example.com)");

    new FeedCommand.SummaryCommand(service, narrator).run();

    verify(sound).notify("新着記事\n注目記事 詳細");
    assertTrue(narrator.wasNarrated());
  }

  @Test
  void blankSummaryDoesNotNotifyAndClearsPreviousNarrationFlag() {
    var service = mock(FeedSummaryService.class);
    var sound = mock(SoundNotificationService.class);
    var narrator = new ChatResponseNarrator(sound);
    narrator.narrateIfCompleted("previous");
    clearInvocations(sound);
    when(service.summarizeBriefing()).thenReturn("   ");

    new FeedCommand.SummaryCommand(service, narrator).run();

    verifyNoInteractions(sound);
    assertFalse(narrator.wasNarrated());
  }

  @Test
  void failedSummaryDoesNotNotifyAndClearsPreviousNarrationFlag() {
    var service = mock(FeedSummaryService.class);
    var sound = mock(SoundNotificationService.class);
    var narrator = new ChatResponseNarrator(sound);
    narrator.narrateIfCompleted("previous");
    clearInvocations(sound);
    when(service.summarizeBriefing()).thenThrow(new IllegalStateException("failed"));

    assertThrows(IllegalStateException.class, () -> new FeedCommand.SummaryCommand(service, narrator).run());

    verifyNoInteractions(sound);
    assertFalse(narrator.wasNarrated());
  }
}
