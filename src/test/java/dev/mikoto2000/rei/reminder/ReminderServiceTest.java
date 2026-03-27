package dev.mikoto2000.rei.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReminderServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void addAtAndListActiveReminders() {
    ReminderService service = newService();

    Reminder created = service.addAt(
        "顧客返信",
        OffsetDateTime.of(2026, 3, 27, 9, 0, 0, 0, ZoneOffset.UTC));

    List<Reminder> reminders = service.listActive();

    assertEquals(1, reminders.size());
    assertEquals(created, reminders.getFirst());
    assertEquals(ReminderType.AT_TIME, reminders.getFirst().type());
    assertFalse(reminders.getFirst().notified());
  }

  @Test
  void addBeforeComputesReminderTime() {
    ReminderService service = newService();

    Reminder created = service.addBefore(
        "定例会議の準備",
        OffsetDateTime.of(2026, 3, 27, 10, 0, 0, 0, ZoneOffset.UTC),
        15);

    assertEquals(ReminderType.BEFORE_TARGET, created.type());
    assertEquals(OffsetDateTime.of(2026, 3, 27, 9, 45, 0, 0, ZoneOffset.UTC), created.remindAt());
    assertEquals(15, created.minutesBefore());
  }

  @Test
  void findDueAndMarkNotified() {
    ReminderService service = newService();
    Reminder due = service.addAt("メール送信", OffsetDateTime.of(2026, 3, 27, 8, 55, 0, 0, ZoneOffset.UTC));
    service.addAt("後で確認", OffsetDateTime.of(2026, 3, 27, 9, 30, 0, 0, ZoneOffset.UTC));

    List<Reminder> dueReminders = service.findDue(OffsetDateTime.of(2026, 3, 27, 9, 0, 0, 0, ZoneOffset.UTC));

    assertEquals(List.of(due), dueReminders);

    Reminder notified = service.markNotified(due.id(), OffsetDateTime.of(2026, 3, 27, 9, 0, 0, 0, ZoneOffset.UTC));

    assertTrue(notified.notified());
    assertEquals(0, service.findDue(OffsetDateTime.of(2026, 3, 27, 9, 0, 0, 0, ZoneOffset.UTC)).size());
    assertEquals(1, service.listActive().size());
  }

  @Test
  void findDueHandlesOffsetDateTimesByInstant() {
    ReminderService service = newService();
    Reminder due = service.addAt("会議の15分前", OffsetDateTime.parse("2026-03-27T13:45:00+09:00"));

    List<Reminder> dueReminders = service.findDue(OffsetDateTime.of(2026, 3, 27, 4, 51, 0, 0, ZoneOffset.UTC));

    assertEquals(List.of(due), dueReminders);
  }

  @Test
  void deleteRemovesReminder() {
    ReminderService service = newService();
    Reminder created = service.addAt("削除対象", OffsetDateTime.of(2026, 3, 27, 11, 0, 0, 0, ZoneOffset.UTC));

    service.delete(created.id());

    assertEquals(0, service.listActive().size());
  }

  private ReminderService newService() {
    return new ReminderService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("reminder.db")));
  }
}
