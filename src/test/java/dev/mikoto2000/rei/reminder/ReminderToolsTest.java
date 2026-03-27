package dev.mikoto2000.rei.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReminderToolsTest {

  @TempDir
  Path tempDir;

  @Test
  void reminderCreateAtAndList() {
    ReminderService service = new ReminderService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("reminder-tools.db")));
    ReminderTools tools = new ReminderTools(service);

    Reminder created = tools.reminderCreate("顧客返信", "2026-03-27T09:00:00Z", null, null);
    List<Reminder> reminders = tools.reminderList();

    assertEquals(created, reminders.getFirst());
    assertEquals(ReminderType.AT_TIME, reminders.getFirst().type());
  }

  @Test
  void reminderCreateBeforeTarget() {
    ReminderService service = new ReminderService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("reminder-tools-before.db")));
    ReminderTools tools = new ReminderTools(service);

    Reminder created = tools.reminderCreate("定例会議の準備", null, "2026-03-27T10:00:00Z", 15);

    assertEquals(OffsetDateTime.of(2026, 3, 27, 9, 45, 0, 0, ZoneOffset.UTC), created.remindAt());
    assertEquals(ReminderType.BEFORE_TARGET, created.type());
  }
}
