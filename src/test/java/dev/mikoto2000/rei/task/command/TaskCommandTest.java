package dev.mikoto2000.rei.task.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskQuery;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.TaskStatus;
import picocli.CommandLine;

class TaskCommandTest {

  @Test
  void addCommandDelegatesToTaskService() {
    TaskService service = Mockito.mock(TaskService.class);
    Task created = task(1L, "設計レビュー", LocalDate.of(2026, 3, 31), 2, TaskStatus.OPEN, List.of("backend", "review"));
    when(service.add(any(), any(), any(Integer.class), any())).thenReturn(created);

    int exitCode = newCommand(service).execute("add", "--due", "2026-03-31", "--priority", "2", "--tag", "backend", "--tag", "review", "設計レビュー");

    assertEquals(0, exitCode);
    verify(service).add("設計レビュー", LocalDate.of(2026, 3, 31), 2, List.of("backend", "review"));
  }

  @Test
  void listCommandPrintsOpenTasks() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.listOpen(any(TaskQuery.class))).thenReturn(List.of(task(1L, "定例MTG", null, 1, TaskStatus.OPEN, List.of("meeting"))));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("list");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("定例MTG"));
  }

  @Test
  void listCommandBuildsQuery() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.listOpen(any(TaskQuery.class))).thenReturn(List.of());

    int exitCode = newCommand(service).execute("list", "--priority", "2", "--tag", "backend", "--due-before", "2026-03-31");

    assertEquals(0, exitCode);
    ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
    verify(service).listOpen(captor.capture());
    TaskQuery actual = captor.getValue();
    assertEquals(2, actual.priority());
    assertEquals("backend", actual.tag());
    assertEquals(LocalDate.of(2026, 3, 31), actual.dueBefore());
  }

  @Test
  void doneAndDeleteCommandsDelegateToTaskService() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.complete(10L)).thenReturn(task(10L, "メール返信", null, 3, TaskStatus.DONE, List.of()));

    assertEquals(0, newCommand(service).execute("done", "10"));
    verify(service).complete(10L);

    assertEquals(0, newCommand(service).execute("delete", "20"));
    verify(service).delete(20L);
  }

  private Task task(long id, String title, LocalDate dueDate, int priority, TaskStatus status, List<String> tags) {
    return new Task(id, title, dueDate, priority, status, tags, OffsetDateTime.now(ZoneOffset.UTC), null);
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
