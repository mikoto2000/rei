package dev.mikoto2000.rei.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.task.Task;
import dev.mikoto2000.rei.task.TaskQuery;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.TaskStatus;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GoogleタスクHTTP通信契約の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class GoogleTaskHttpContractTest {
  /** 旧形式タグ一覧を受け付けないことを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", "[\"research\"]"})
  void directTaskCreationRejectsLegacyTags(String tags) throws Exception {
    var tasks = mock(TaskService.class);
    var calendar = mock(GoogleCalendarService.class);
    var runs = mock(IntegrationRunSubmitService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new GoogleOperationsController(calendar, tasks, runs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mvc.perform(
            post("/api/v1/google-tasks")
                .contentType("application/json")
                .content("{\"title\":\"Review\",\"tags\":" + tags + "}"))
        .andExpect(status().isBadRequest());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(tasks, calendar, runs);
  }

  /** 旧形式タグ一覧を受け付けないことを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"[]", "null", "[\"research\"]"})
  void queuedTaskCreationRejectsLegacyTags(String tags) throws Exception {
    var runs = mock(IntegrationRunSubmitService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new GoogleQueuedController(runs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mvc.perform(
            post("/api/v1/google-operations/tasks/add")
                .contentType("application/json")
                .content(
                    "{\"projectId\":\"project\",\"sessionId\":\"session\",\"title\":\"Review\",\"tags\":"
                        + tags
                        + "}"))
        .andExpect(status().isBadRequest());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(runs);
  }

  /** 待機中タスク一覧について、旧形式タグを受け付けないことを確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"null", "\"research\""})
  void queuedTaskListRejectsLegacyTag(String tag) throws Exception {
    var runs = mock(IntegrationRunSubmitService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new GoogleQueuedController(runs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mvc.perform(
            post("/api/v1/google-operations/tasks/list")
                .contentType("application/json")
                .content("{\"projectId\":\"project\",\"tag\":" + tag + "}"))
        .andExpect(status().isBadRequest());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(runs);
  }

  /** 残量項目一覧を保持することを確認する。 */
  @Test
  void directTaglessTaskCreateAndListPreserveRemainingFieldsAndFourColumns() throws Exception {
    var tasks = mock(TaskService.class);
    var calendar = mock(GoogleCalendarService.class);
    var runs = mock(IntegrationRunSubmitService.class);
    var due = LocalDate.of(2026, 10, 20);
    var task = new Task(7, "Review", due, 2, TaskStatus.OPEN, OffsetDateTime.now(), null);
    when(tasks.add("Review", due, 2)).thenReturn(task);
    when(tasks.listOpen(new TaskQuery(2, due))).thenReturn(List.of(task));
    var mvc =
        MockMvcBuilders.standaloneSetup(new GoogleOperationsController(calendar, tasks, runs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mvc.perform(
            post("/api/v1/google-tasks")
                .contentType("application/json")
                .content("{\"title\":\"Review\",\"due\":\"2026-10-20\",\"priority\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("追加: 7 | Review"));
    mvc.perform(get("/api/v1/google-tasks").param("priority", "2").param("dueBefore", "2026-10-20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("7 | 2 | 2026-10-20 | Review"));
    verify(tasks).add("Review", due, 2);
    verify(tasks).listOpen(new TaskQuery(2, due));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(calendar, runs);
  }

  /** 所有権日付一覧を保持することを確認する。 */
  @Test
  void queuedTaglessTaskCreateAndListPreserveOwnershipDatesAndDefaults() throws Exception {
    var runs = mock(IntegrationRunSubmitService.class);
    var due = LocalDate.of(2026, 10, 20);
    var run =
        new AgentRunContext(
            "run", "session", Path.of("."), "project", AgentRunContext.RequestSource.WEB);
    when(runs.addGoogleTask("project", "session", "Review", due, 3)).thenReturn(run);
    when(runs.listGoogleTasks("project", "session", 2, due)).thenReturn(run);
    var mvc =
        MockMvcBuilders.standaloneSetup(new GoogleQueuedController(runs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mvc.perform(
            post("/api/v1/google-operations/tasks/add")
                .contentType("application/json")
                .content(
                    "{\"projectId\":\"project\",\"sessionId\":\"session\",\"title\":\"Review\",\"due\":\"2026-10-20\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value("run"));
    mvc.perform(
            post("/api/v1/google-operations/tasks/list")
                .contentType("application/json")
                .content(
                    "{\"projectId\":\"project\",\"sessionId\":\"session\",\"priority\":2,\"dueBefore\":\"2026-10-20\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value("run"));
    verify(runs).addGoogleTask("project", "session", "Review", due, 3);
    verify(runs).listGoogleTasks("project", "session", 2, due);
  }

  /** Web APIによる操作で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unrelatedUnknownWriteFieldsRemainRejected() throws Exception {
    var runs = mock(IntegrationRunSubmitService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new GoogleQueuedController(runs))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mvc.perform(
            post("/api/v1/google-operations/tasks/add")
                .contentType("application/json")
                .content("{\"projectId\":\"project\",\"title\":\"Review\",\"root\":\"C:/other\"}"))
        .andExpect(status().isBadRequest());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(runs);
  }
}
