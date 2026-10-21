package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.TaskStatus;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/** Google操作一覧受付窓口の結果と状態の契約を検証するテスト。 */
class GoogleOperationsControllerTest {
  /** タスク一覧について、既存のサービスを利用することを確認する。 */
  @Test
  void tasksUseTheExistingServiceAndDefaultPriority() {
    var calendar = mock(GoogleCalendarService.class);
    var tasks = mock(TaskService.class);
    var runs = mock(IntegrationRunSubmitService.class);
    var task = new Task(7, "Review", null, 3, TaskStatus.OPEN, OffsetDateTime.now(), null);
    when(tasks.add("Review", null, 3)).thenReturn(task);
    var api = new GoogleOperationsController(calendar, tasks, runs);
    assertEquals(
        "追加: 7 | Review",
        api.addTask(new GoogleOperationsController.Task("Review", null, null)).text());
    verify(tasks).add("Review", null, 3);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(calendar, runs);
    assertThrows(
        IllegalArgumentException.class,
        () -> api.addTask(new GoogleOperationsController.Task("", null, null)));
  }

  /** Web APIによる操作で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void authorizationPassesCapturedOwnershipAndReceiptToTheQueue() {
    var calendar = mock(GoogleCalendarService.class);
    var tasks = mock(TaskService.class);
    var runs = mock(IntegrationRunSubmitService.class);
    var run =
        new AgentRunContext(
            "run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            AgentRunContext.RequestSource.WEB);
    when(runs.authorizeCalendar("project", "session", "key")).thenReturn(run);
    var api = new GoogleOperationsController(calendar, tasks, runs);
    var accepted =
        api.authorizeCalendar(new GoogleOperationsController.Scope("project", "session"), "key");
    assertEquals(202, accepted.getStatusCode().value());
    assertEquals("run", accepted.getBody().runId());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(calendar, tasks);
  }
}
