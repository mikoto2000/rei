package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ActivityReportsTest {
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
    verifyNoInteractions(capture);
    assertEquals("Activity Capture を一時停止しました。", command.reportPause());
    verify(capture).pause();
  }

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
