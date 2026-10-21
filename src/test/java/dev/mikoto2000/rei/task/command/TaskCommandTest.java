package dev.mikoto2000.rei.task.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskQuery;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.TaskStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import picocli.CommandLine;

/** タスクコマンドの結果と状態の契約を検証するテスト。 */
class TaskCommandTest {

  /** 追加コマンドについて、削除済みタグ選択項目を受け付けないことを確認する。 */
  @Test
  void addCommandRejectsRemovedTagOption() {
    TaskService service = Mockito.mock(TaskService.class);

    assertThrows(
        CommandLine.UnmatchedArgumentException.class,
        () -> newCommand(service).parseArgs("add", "--tag", "backend", "design review"));
    Mockito.verifyNoInteractions(service);
  }

  /** 一覧コマンドについて、削除済みタグ選択項目を受け付けないことを確認する。 */
  @Test
  void listCommandRejectsRemovedTagOption() {
    TaskService service = Mockito.mock(TaskService.class);

    assertThrows(
        CommandLine.UnmatchedArgumentException.class,
        () -> newCommand(service).parseArgs("list", "--tag", "backend"));
    Mockito.verifyNoInteractions(service);
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listReportOmitsRemovedTagColumn() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.listOpen(any(TaskQuery.class)))
        .thenReturn(
            List.of(task(1L, "design review", LocalDate.of(2026, 3, 31), 2, TaskStatus.OPEN)));

    assertEquals("1 | 2 | 2026-03-31 | design review", TaskCommand.reportList(service, null, null));
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void authCommandDelegatesToTaskService() throws Exception {
    TaskService service = Mockito.mock(TaskService.class);

    int exitCode = newCommand(service).execute("auth");

    assertEquals(0, exitCode);
    verify(service).authorize();
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void addCommandDelegatesToTaskService() {
    TaskService service = Mockito.mock(TaskService.class);
    Task created = task(1L, "設計レビュー", LocalDate.of(2026, 3, 31), 2, TaskStatus.OPEN);
    when(service.add(any(), any(), any(Integer.class))).thenReturn(created);

    int exitCode =
        newCommand(service).execute("add", "--due", "2026-03-31", "--priority", "2", "設計レビュー");

    assertEquals(0, exitCode);
    verify(service).add("設計レビュー", LocalDate.of(2026, 3, 31), 2);
  }

  /** タスクの管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void addCommandPrintsErrorWhenTaskCreationFails() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.add(any(), any(), any(Integer.class)))
        .thenThrow(
            new IllegalStateException(
                "Google Tasks へのタスク追加に失敗しました",
                new IllegalStateException("Google Task integration is disabled")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("add", "設計レビュー");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(
        out.toString()
            .contains("[error] Google Tasks へのタスク追加に失敗しました: Google Task integration is disabled"));
  }

  /** タスクの管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listCommandPrintsOpenTasks() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.listOpen(any(TaskQuery.class)))
        .thenReturn(List.of(task(1L, "定例MTG", null, 1, TaskStatus.OPEN)));

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

  /** タスクの管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void listCommandBuildsQuery() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.listOpen(any(TaskQuery.class))).thenReturn(List.of());

    int exitCode =
        newCommand(service).execute("list", "--priority", "2", "--due-before", "2026-03-31");

    assertEquals(0, exitCode);
    ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
    verify(service).listOpen(captor.capture());
    TaskQuery actual = captor.getValue();
    assertEquals(2, actual.priority());
    assertEquals(LocalDate.of(2026, 3, 31), actual.dueBefore());
  }

  /** 完了およびについて、コマンド一覧委譲先を削除することを確認する。 */
  @Test
  void doneAndDeleteCommandsDelegateToTaskService() {
    TaskService service = Mockito.mock(TaskService.class);
    when(service.complete(10L)).thenReturn(task(10L, "メール返信", null, 3, TaskStatus.DONE));

    assertEquals(0, newCommand(service).execute("done", "10"));
    verify(service).complete(10L);

    assertEquals(0, newCommand(service).execute("delete", "20"));
    verify(service).delete(20L);
  }

  /** {@code task} の条件を再現するための補助情報を返す。 */
  private Task task(long id, String title, LocalDate dueDate, int priority, TaskStatus status) {
    return new Task(id, title, dueDate, priority, status, OffsetDateTime.now(ZoneOffset.UTC), null);
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(TaskService service) {
    return new CommandLine(
        new TaskCommand(),
        new CommandLine.IFactory() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          public <K> K create(Class<K> cls) throws Exception {
            if (cls == TaskCommand.AuthCommand.class) {
              return cls.cast(new TaskCommand.AuthCommand(service));
            }
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
