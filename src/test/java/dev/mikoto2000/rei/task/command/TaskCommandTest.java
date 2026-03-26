package dev.mikoto2000.rei.task.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import dev.mikoto2000.rei.task.TaskService;
import picocli.CommandLine;

class TaskCommandTest {

  @TempDir
  Path tempDir;

  @Test
  void addCommandCreatesTask() {
    TaskService service = newService();

    int exitCode = newCommand(service).execute("add", "--due", "2026-03-31", "--priority", "2", "--tag", "backend", "--tag", "review", "設計レビュー");

    assertEquals(0, exitCode);
    assertEquals(1, service.listOpen().size());
    assertEquals("設計レビュー", service.listOpen().getFirst().title());
  }

  @Test
  void listCommandPrintsOpenTasks() {
    TaskService service = newService();
    newCommand(service).execute("add", "--priority", "1", "議事録作成");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("list");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("議事録作成"));
  }

  @Test
  void doneAndDeleteCommandsUpdateTasks() {
    TaskService service = newService();
    newCommand(service).execute("add", "--priority", "1", "メール返信");
    long id = service.listOpen().getFirst().id();

    assertEquals(0, newCommand(service).execute("done", Long.toString(id)));
    assertEquals(0, service.listOpen().size());

    newCommand(service).execute("add", "資料作成");
    long deleteId = service.listOpen().getFirst().id();
    assertEquals(0, newCommand(service).execute("delete", Long.toString(deleteId)));
    assertEquals(0, service.listOpen().size());
  }

  private TaskService newService() {
    return new TaskService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("task-command.db")));
  }

  private CommandLine newCommand(TaskService service) {
    return new CommandLine(new TaskCommand(), new CommandLine.IFactory() {
      @Override
      public <K> K create(Class<K> cls) throws Exception {
        if (cls == TaskCommand.AddCommand.class) {
          return cls.cast(new TaskCommand.AddCommand(service));
        }
        if (cls == TaskCommand.ListCommand.class) {
          return cls.cast(new TaskCommand.ListCommand(service));
        }
        if (cls == TaskCommand.DoneCommand.class) {
          return cls.cast(new TaskCommand.DoneCommand(service));
        }
        if (cls == TaskCommand.DeleteCommand.class) {
          return cls.cast(new TaskCommand.DeleteCommand(service));
        }
        return CommandLine.defaultFactory().create(cls);
      }
    });
  }
}
