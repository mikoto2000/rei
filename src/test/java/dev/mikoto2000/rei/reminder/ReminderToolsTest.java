package dev.mikoto2000.rei.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReminderToolsTest {

  @TempDir
  Path tempDir;

  private ToolCallback createCallback(ReminderTools tools) {
    return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
        .filter(callback -> "reminderCreate".equals(callback.getToolDefinition().name()))
        .findFirst().orElseThrow();
  }

  @Test
  void onlyMessageIsRequiredInToolSchema() throws Exception {
    ReminderTools tools = new ReminderTools(null);
    var schema = new ObjectMapper().readTree(createCallback(tools).getToolDefinition().inputSchema());
    assertEquals(new ObjectMapper().readTree("[\"message\"]"), schema.get("required"));
  }

  static Stream<String> atTimeArguments() {
    return Stream.of(
        "",
        ",\"targetAt\":null,\"minutesBefore\":null",
        ",\"targetAt\":\"\",\"minutesBefore\":null",
        ",\"targetAt\":\"   \"");
  }

  @ParameterizedTest
  @MethodSource("atTimeArguments")
  void callbackCreatesAtTimeWithUnusedArguments(String unusedArguments) {
    ReminderService service = new ReminderService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("callback.db")));
    createCallback(new ReminderTools(service)).call(
        "{\"message\":\"就業規則を確認\",\"remindAt\":\"2026-09-07T09:00:00+09:00\"" + unusedArguments + "}");
    var reminders = service.listActive();
    assertEquals(1, reminders.size());
    assertEquals(ReminderType.AT_TIME, reminders.getFirst().type());
    assertEquals(OffsetDateTime.parse("2026-09-07T09:00:00+09:00"), reminders.getFirst().remindAt());
  }

  @Test
  void callbackCreatesBeforeTargetWithRemindAtOmitted() {
    ReminderService service = new ReminderService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("callback-before.db")));
    createCallback(new ReminderTools(service)).call(
        """
        {"message":"会議準備","targetAt":"2026-09-07T09:00:00+09:00","minutesBefore":15}
        """);
    var created = service.listActive().getFirst();
    assertEquals(ReminderType.BEFORE_TARGET, created.type());
    assertEquals(OffsetDateTime.parse("2026-09-07T08:45:00+09:00"), created.remindAt());
  }

  @Test
  void rejectsConflictingOrIncompleteTimeArguments() {
    ReminderTools tools = new ReminderTools(null);
    String at = "2026-09-07T09:00:00+09:00";
    assertThrows(IllegalArgumentException.class, () -> tools.reminderCreate("確認", at, at, 15));
    assertThrows(IllegalArgumentException.class, () -> tools.reminderCreate("確認", at, null, 0));
    assertThrows(IllegalArgumentException.class, () -> tools.reminderCreate("確認", null, at, null));
    assertThrows(IllegalArgumentException.class, () -> tools.reminderCreate("確認", null, null, 15));
    assertThrows(IllegalArgumentException.class, () -> tools.reminderCreate("確認", null, null, null));
  }

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
