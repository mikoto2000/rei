package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.briefing.BriefingService;
import dev.mikoto2000.rei.briefing.DailyBriefing;
import dev.mikoto2000.rei.briefing.command.BriefingCommand;
import dev.mikoto2000.rei.reminder.ReminderService;
import dev.mikoto2000.rei.reminder.command.ReminderCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 補助伝えるの結果と状態の契約を検証するテスト。 */
class UtilityReportsTest {
  /** Web APIによる操作で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void emptyRemindersAndBriefingHaveShellTextInsteadOfJson() throws Exception {
    var reminders = mock(ReminderService.class);
    when(reminders.listActive()).thenReturn(List.of());
    assertEquals("未通知のリマインドはありません", ReminderCommand.reportList(reminders));
    var briefing = mock(BriefingService.class);
    when(briefing.today())
        .thenReturn(
            new DailyBriefing(
                java.time.LocalDate.of(2026, 10, 11),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "overview",
                List.of(),
                List.of()));
    var text = BriefingCommand.reportToday(briefing);
    assertTrue(text.contains("日次ブリーフィング | 2026-10-11"));
    assertTrue(text.contains("- 予定はありません"));
    assertTrue(text.contains("- 未完了タスクはありません"));
  }
}
