package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 作業状況診断情報コマンドの結果と状態の契約を検証するテスト。 */
class ActivityDiagnosticsCommandTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"", "today", "yesterday", "2026-09-23"})
  void verboseWorksWithEveryDateAndDoesNotRunCapture(String day) {
    var store = mock(ActivityStore.class);
    var capture = mock(ActivityCapture.class);
    when(store.findRecordsBetween(any(), any()))
        .thenAnswer(
            inv -> {
              var r = ActivitySemanticTest.dev(0, "Terminal", "X");
              return List.of(
                  new ActivityRecord(
                      r.id(),
                      ((Instant) inv.getArgument(0)).plusSeconds(3600),
                      60,
                      r.observations(),
                      r.foreground(),
                      r.inference(),
                      r.confidence(),
                      List.of(),
                      0,
                      false));
            });
    var timeline =
        new ActivityTimeline(store, Clock.fixed(ActivitySemanticTest.START, ZoneOffset.UTC));
    var command =
        new picocli.CommandLine(new ActivityCommand(timeline, capture, new ActivityProperties()));
    var out = new StringWriter();
    command.setOut(new PrintWriter(out));
    command.setErr(new PrintWriter(new StringWriter()));
    assertEquals(
        0, day.isEmpty() ? command.execute("--verbose") : command.execute(day, "--verbose"));
    assertTrue(out.toString().contains("evidence:"));
    assertTrue(out.toString().contains("confidence:"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(capture);
  }

  /** 作業状況の把握で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void verboseBeforeDateIsAcceptedAndSummaryStillUsesOnlyTrendPath() {
    var timeline = mock(ActivityTimeline.class);
    var presentation = mock(ActivityTimelinePresentationService.class);
    var activity =
        new ActivityCommand(timeline, mock(ActivityCapture.class), new ActivityProperties());
    activity.timelinePresentation(presentation);
    var command = new picocli.CommandLine(activity);
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute("--verbose", "yesterday"));
    verify(presentation).format("yesterday", true);
    assertEquals(0, command.execute("today"));
    verify(presentation).format("today", false);
    clearInvocations(presentation);
    assertEquals(0, command.execute("summary", "yesterday"));
    verify(timeline).trendSummary("yesterday");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(presentation);
    assertTrue(command.getUsageMessage().contains("--verbose"));
  }
}
