package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** 作業状況伝えるの結果と状態の契約を検証するテスト。 */
class ActivityReportsTest {
  /** 利用新しいコマンド状態を伝えることを確認する。 */
  @Test
  void reportsUseFreshCommandStateAndKeepSummarySeparateFromVerboseTimeline() {
    var timeline = mock(ActivityTimeline.class);
    var capture = mock(ActivityCapture.class);
    var presentation = mock(ActivityTimelinePresentationService.class);
    when(presentation.format("yesterday", true)).thenReturn("timeline");
    when(timeline.trendSummary("today")).thenReturn("summary");
    var command = new ActivityCommand(timeline, capture, new ActivityProperties());
    command.timelinePresentation(presentation);
    assertEquals("timeline", command.reportTimeline("yesterday", true));
    assertEquals("summary", command.reportSummary("today"));
    verify(presentation).format("yesterday", true);
    verify(timeline).trendSummary("today");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(capture);
    assertEquals("Activity Capture を一時停止しました。", command.reportPause());
    verify(capture).pause();
  }

  /** 分類および作業習慣伝えるについて、既存のメッセージ一覧を保持することを確認する。 */
  @Test
  void classificationAndBehaviorReportsPreserveTheExistingMessages() {
    var toolkit = mock(ClassificationToolkit.class);
    when(toolkit.status()).thenReturn("");
    var behavior = mock(dev.mikoto2000.rei.activity.behavior.BehaviorService.class);
    when(behavior.status()).thenReturn("behavior status");
    var command =
        new ActivityCommand(
            mock(ActivityTimeline.class),
            mock(ActivityCapture.class),
            new ActivityProperties(),
            behavior);
    command.operationalToolkit(toolkit, mock(ClassificationRuleSuggestions.class));
    assertEquals("候補なし。", command.reportClassificationStatus());
    assertEquals("behavior status", command.reportBehaviorStatus());
    assertEquals("Behavior を無効にしました。", command.reportBehaviorEnabled(false));
    verify(behavior).setEnabled(false);
  }
}
