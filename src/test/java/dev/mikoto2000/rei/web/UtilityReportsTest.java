package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.briefing.*;
import dev.mikoto2000.rei.briefing.command.BriefingCommand;
import dev.mikoto2000.rei.reminder.*;
import dev.mikoto2000.rei.reminder.command.ReminderCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

class UtilityReportsTest {
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
