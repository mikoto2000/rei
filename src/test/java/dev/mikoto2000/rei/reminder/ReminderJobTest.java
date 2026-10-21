package dev.mikoto2000.rei.reminder;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** リマインダー実行する作業の結果と状態の契約を検証するテスト。 */
class ReminderJobTest {

  /** リマインダーの管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void runDueRemindersPrintsNarratesAndMarksThemNotified() {
    ReminderService service = org.mockito.Mockito.mock(ReminderService.class);
    SoundNotificationService sound = org.mockito.Mockito.mock(SoundNotificationService.class);
    ReminderJob job =
        new ReminderJob(service, sound) {
          /** {@code now} の条件を再現するための補助情報を返す。 */
          @Override
          OffsetDateTime now() {
            return OffsetDateTime.of(2026, 3, 27, 9, 0, 0, 0, ZoneOffset.UTC);
          }
        };
    Reminder reminder =
        new Reminder(
            1L,
            "顧客返信",
            ReminderType.AT_TIME,
            OffsetDateTime.of(2026, 3, 27, 8, 55, 0, 0, ZoneOffset.UTC),
            null,
            null,
            false,
            OffsetDateTime.of(2026, 3, 27, 8, 0, 0, 0, ZoneOffset.UTC),
            null);
    when(service.findDue(OffsetDateTime.of(2026, 3, 27, 9, 0, 0, 0, ZoneOffset.UTC)))
        .thenReturn(List.of(reminder));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      job.runDueReminders();
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("リマインド"));
    assertTrue(out.toString().contains("顧客返信"));
    verify(sound).notify("リマインドです。顧客返信");
    verify(service).markNotified(1L, OffsetDateTime.of(2026, 3, 27, 9, 0, 0, 0, ZoneOffset.UTC));
  }
}
