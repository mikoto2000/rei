package dev.mikoto2000.rei.task;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.tasks.Tasks;
import com.google.api.services.tasks.TasksScopes;

import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;

@Service
public class TaskService {

  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  private static final List<String> SCOPES = List.of(CalendarScopes.CALENDAR_EVENTS, TasksScopes.TASKS);
  private static final String DEFAULT_TASK_LIST_ID = "@default";

  private final GoogleCalendarProperties googleCalendarProperties;

  @Autowired
  public TaskService(GoogleCalendarProperties googleCalendarProperties) {
    this.googleCalendarProperties = googleCalendarProperties;
  }

  public TaskService(javax.sql.DataSource ignoredDataSource) {
    this(new GoogleCalendarProperties(
        "Rei",
        "",
        "",
        new GoogleCalendarProperties.CalendarProperties(false, "primary", ""),
        new GoogleCalendarProperties.TaskProperties(true)));
  }

  public Task add(String title, LocalDate dueDate, int priority, List<String> tags) {
    try {
      com.google.api.services.tasks.model.Task request = new com.google.api.services.tasks.model.Task();
      request.setTitle(title);
      if (dueDate != null) {
        request.setDue(new DateTime(dueDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()).toStringRfc3339());
      }
      com.google.api.services.tasks.model.Task created = getTasksClient().tasks().insert(DEFAULT_TASK_LIST_ID, request).execute();
      return toTask(created);
    } catch (Exception e) {
      throw new IllegalStateException("Google Tasks へのタスク追加に失敗しました", e);
    }
  }

  public List<Task> listOpen() {
    return listOpen(new TaskQuery(null, null, null));
  }

  public List<Task> listOpen(TaskQuery query) {
    try {
      List<com.google.api.services.tasks.model.Task> items = getTasksClient().tasks().list(DEFAULT_TASK_LIST_ID)
          .setShowCompleted(false)
          .setShowHidden(false)
          .setMaxResults(100)
          .execute()
          .getItems();
      if (items == null) {
        return List.of();
      }
      return items.stream()
          .map(this::toTask)
          .filter(task -> query.dueBefore() == null || (task.dueDate() != null && !task.dueDate().isAfter(query.dueBefore())))
          .toList();
    } catch (Exception e) {
      throw new IllegalStateException("Google Tasks の一覧取得に失敗しました", e);
    }
  }

  public Task complete(long id) {
    try {
      com.google.api.services.tasks.model.Task current = fetchByHashId(id, true);
      current.setStatus("completed");
      com.google.api.services.tasks.model.Task updated = getTasksClient().tasks()
          .update(DEFAULT_TASK_LIST_ID, current.getId(), current)
          .execute();
      return toTask(updated);
    } catch (Exception e) {
      throw new IllegalStateException("Google Tasks の完了更新に失敗しました", e);
    }
  }

  public Task update(long id, String title, LocalDate dueDate, Integer priority, List<String> tags) {
    try {
      com.google.api.services.tasks.model.Task current = fetchByHashId(id, true);
      if (title != null && !title.isBlank()) {
        current.setTitle(title);
      }
      if (dueDate != null) {
        current.setDue(new DateTime(dueDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()).toStringRfc3339());
      }
      com.google.api.services.tasks.model.Task updated = getTasksClient().tasks()
          .update(DEFAULT_TASK_LIST_ID, current.getId(), current)
          .execute();
      return toTask(updated);
    } catch (Exception e) {
      throw new IllegalStateException("Google Tasks の更新に失敗しました", e);
    }
  }

  public Task updateDeadline(long id, LocalDate dueDate) {
    try {
      com.google.api.services.tasks.model.Task current = fetchByHashId(id, true);
      current.setDue(dueDate == null ? null
          : new DateTime(dueDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()).toStringRfc3339());
      com.google.api.services.tasks.model.Task updated = getTasksClient().tasks()
          .update(DEFAULT_TASK_LIST_ID, current.getId(), current)
          .execute();
      return toTask(updated);
    } catch (Exception e) {
      throw new IllegalStateException("Google Tasks の期限更新に失敗しました", e);
    }
  }

  public void delete(long id) {
    try {
      com.google.api.services.tasks.model.Task current = fetchByHashId(id, true);
      getTasksClient().tasks().delete(DEFAULT_TASK_LIST_ID, current.getId()).execute();
    } catch (Exception e) {
      throw new IllegalStateException("Google Tasks の削除に失敗しました", e);
    }
  }

  public void refreshGoogleToken() throws Exception {
    Credential credential = authorizeCredential(GoogleNetHttpTransport.newTrustedTransport());
    boolean refreshed = credential.refreshToken();
    if (!refreshed) {
      throw new IllegalStateException("Google token refresh failed");
    }
  }

  private com.google.api.services.tasks.model.Task fetchByHashId(long id, boolean includeCompleted) throws Exception {
    List<com.google.api.services.tasks.model.Task> items = getTasksClient().tasks().list(DEFAULT_TASK_LIST_ID)
        .setShowCompleted(includeCompleted)
        .setShowHidden(true)
        .setMaxResults(100)
        .execute()
        .getItems();
    if (items == null) {
      throw new IllegalStateException("Task not found: " + id);
    }
    return items.stream()
        .filter(task -> stableTaskId(task.getId()) == id)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Task not found: " + id));
  }

  private Task toTask(com.google.api.services.tasks.model.Task task) {
    LocalDate dueDate = task.getDue() == null ? null : OffsetDateTime.parse(task.getDue()).toLocalDate();
    OffsetDateTime updatedAt = task.getUpdated() == null
        ? OffsetDateTime.now(ZoneOffset.UTC)
        : OffsetDateTime.parse(task.getUpdated());
    OffsetDateTime completedAt = task.getCompleted() == null ? null : OffsetDateTime.parse(task.getCompleted());
    TaskStatus status = "completed".equalsIgnoreCase(task.getStatus()) ? TaskStatus.DONE : TaskStatus.OPEN;

    return new Task(
        stableTaskId(task.getId()),
        Objects.toString(task.getTitle(), "(no title)"),
        dueDate,
        3,
        status,
        List.of(),
        updatedAt,
        completedAt);
  }

  private long stableTaskId(String taskId) {
    return Integer.toUnsignedLong(taskId.hashCode());
  }

  private Tasks getTasksClient() throws Exception {
    if (!googleCalendarProperties.task().enabled()) {
      throw new IllegalStateException("Google Task integration is disabled");
    }
    NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    return new Tasks.Builder(httpTransport, JSON_FACTORY, authorizeCredential(httpTransport))
        .setApplicationName(googleCalendarProperties.applicationName())
        .build();
  }

  private Credential authorizeCredential(NetHttpTransport httpTransport) throws Exception {
    Path credentialsPath = Path.of(googleCalendarProperties.credentialsPath());
    if (!Files.exists(credentialsPath)) {
      throw new IllegalStateException("Google OAuth credentials file was not found: " + credentialsPath);
    }
    Files.createDirectories(Path.of(googleCalendarProperties.tokensDirectory()));

    try (InputStream in = Files.newInputStream(credentialsPath)) {
      GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
      AuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
          httpTransport,
          JSON_FACTORY,
          clientSecrets,
          SCOPES)
          .setDataStoreFactory(new FileDataStoreFactory(Path.of(googleCalendarProperties.tokensDirectory()).toFile()))
          .setAccessType("offline")
          .build();

      LocalServerReceiver receiver = new LocalServerReceiver.Builder()
          .setHost("127.0.0.1")
          .setPort(8888)
          .build();

      AuthorizationCodeInstalledApp app = new AuthorizationCodeInstalledApp(flow, receiver, this::browse);
      return app.authorize("user");
    }
  }

  private void browse(String url) throws IOException {
    IO.println("Google OAuth を開始します。ブラウザが開かない場合は次の URL を開いてください:");
    IO.println(url);
    if (Desktop.isDesktopSupported()) {
      Desktop desktop = Desktop.getDesktop();
      if (desktop.isSupported(Desktop.Action.BROWSE)) {
        desktop.browse(URI.create(url));
      }
    }
  }
}
