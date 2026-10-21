package dev.mikoto2000.rei.feed.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.feed.FeedSummaryService;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import org.junit.jupiter.api.Test;

/** フィード要約Narrationの結果と状態の契約を検証するテスト。 */
class FeedSummaryNarrationTest {

  /** フィードの購読と収集で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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

  /** フィードの購読と収集で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void blankSummaryDoesNotNotifyAndClearsPreviousNarrationFlag() {
    var service = mock(FeedSummaryService.class);
    var sound = mock(SoundNotificationService.class);
    var narrator = new ChatResponseNarrator(sound);
    narrator.narrateIfCompleted("previous");
    clearInvocations(sound);
    when(service.summarizeBriefing()).thenReturn("   ");

    new FeedCommand.SummaryCommand(service, narrator).run();

    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(sound);
    assertFalse(narrator.wasNarrated());
  }

  /** フィードの購読と収集で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedSummaryDoesNotNotifyAndClearsPreviousNarrationFlag() {
    var service = mock(FeedSummaryService.class);
    var sound = mock(SoundNotificationService.class);
    var narrator = new ChatResponseNarrator(sound);
    narrator.narrateIfCompleted("previous");
    clearInvocations(sound);
    when(service.summarizeBriefing()).thenThrow(new IllegalStateException("failed"));

    assertThrows(
        IllegalStateException.class, () -> new FeedCommand.SummaryCommand(service, narrator).run());

    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(sound);
    assertFalse(narrator.wasNarrated());
  }
}
