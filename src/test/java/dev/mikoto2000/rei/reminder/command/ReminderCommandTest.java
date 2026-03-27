package dev.mikoto2000.rei.reminder.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.reminder.ReminderService;
import picocli.CommandLine;

class ReminderCommandTest {

  @TempDir
  Path tempDir;

  @Test
  void addAndListAtTimeReminder() {
    ReminderService service = newService();

    int addExitCode = newCommand(service).execute(
        "add",
        "--at",
        "2026-03-27T09:00:00Z",
        "顧客返信");

    assertEquals(0, addExitCode);
    assertEquals(1, service.listActive().size());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int listExitCode = newCommand(service).execute("list");
      assertEquals(0, listExitCode);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("顧客返信"));
    assertTrue(output.contains("2026-03-27T09:00Z"));
  }

  @Test
  void addBeforeTargetReminderAndDelete() {
    ReminderService service = newService();

    int addExitCode = newCommand(service).execute(
        "add",
        "--target",
        "2026-03-27T10:00:00Z",
        "--minutes-before",
        "15",
        "定例会議の準備");

    assertEquals(0, addExitCode);
    assertEquals(OffsetDateTime.of(2026, 3, 27, 9, 45, 0, 0, ZoneOffset.UTC), service.listActive().getFirst().remindAt());

    long id = service.listActive().getFirst().id();
    assertEquals(0, newCommand(service).execute("delete", Long.toString(id)));
    assertEquals(0, service.listActive().size());
  }

  private ReminderService newService() {
    return new ReminderService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("reminder-command.db")));
  }

  private CommandLine newCommand(ReminderService service) {
    return new CommandLine(new ReminderCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == ReminderCommand.AddCommand.class) {
          return cls.cast(new ReminderCommand.AddCommand(service));
        }
        if (cls == ReminderCommand.ListCommand.class) {
          return cls.cast(new ReminderCommand.ListCommand(service));
        }
        if (cls == ReminderCommand.DeleteCommand.class) {
          return cls.cast(new ReminderCommand.DeleteCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
