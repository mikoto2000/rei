package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.task.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class GoogleOperationsControllerTest {
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
    verifyNoInteractions(calendar, runs);
    assertThrows(
        IllegalArgumentException.class,
        () -> api.addTask(new GoogleOperationsController.Task("", null, null)));
  }

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
    verifyNoInteractions(calendar, tasks);
  }
}
