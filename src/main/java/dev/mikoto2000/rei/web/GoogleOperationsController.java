package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarService;
import dev.mikoto2000.rei.googlecalendar.command.ScheduleCommand;
import dev.mikoto2000.rei.task.TaskService;
import dev.mikoto2000.rei.task.command.TaskCommand;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Google操作一覧をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public final class GoogleOperationsController {
  /** 予定表。 */
  private final GoogleCalendarService calendar;

  /** タスク一覧。 */
  private final TaskService tasks;

  /** 実行一覧。 */
  private final IntegrationRunSubmitService runs;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す対象範囲を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   */
  public record Scope(String projectId, String sessionId) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すイベントを表す。
   *
   * @param title タイトル
   * @param start 開始
   * @param end 終了
   * @param location 場所
   * @param description 説明
   */
  public record Event(String title, String start, String end, String location, String description)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すタスクを表す。
   *
   * @param title タイトル
   * @param due 期限
   * @param priority 優先度
   */
  public record Task(String title, LocalDate due, Integer priority) implements StrictApiRequest {}

  /** 指定された値や利用先を使って、GoogleOperationsControllerを初期化する。 */
  public GoogleOperationsController(
      GoogleCalendarService calendar, TaskService tasks, IntegrationRunSubmitService runs) {
    this.calendar = calendar;
    this.tasks = tasks;
    this.runs = runs;
  }

  /** Web APIによる操作で用いるイベント一覧を返す。 */
  @GetMapping("/google-calendar/events")
  public Text events(
      @RequestParam(required = false) LocalDate date,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    optional(from);
    optional(to);
    return text(ScheduleCommand.reportList(calendar, date, from, to));
  }

  /** 新たな対象を管理できるよう、イベントを追加する。 */
  @PostMapping("/google-calendar/events")
  public Text addEvent(@RequestBody Event request) {
    if (request == null) throw new IllegalArgumentException("Event required");
    required(request.title());
    required(request.start());
    required(request.end());
    optional(request.location());
    optional(request.description());
    return text(
        ScheduleCommand.reportAdd(
            calendar,
            request.title(),
            request.start(),
            request.end(),
            request.location(),
            request.description()));
  }

  /** Web APIによる操作で用いる権限の確認予定表を返す。 */
  @PostMapping("/google-calendar/authorize")
  public ResponseEntity<ChatResponse> authorizeCalendar(
      @RequestBody Scope request, @RequestHeader("Idempotency-Key") String key) {
    scope(request);
    return accepted(runs.authorizeCalendar(request.projectId(), request.sessionId(), key));
  }

  /** Web APIによる操作で用いる再確認を返す。 */
  @PostMapping("/google-calendar/refresh-token")
  public ResponseEntity<ChatResponse> refresh(
      @RequestBody Scope request, @RequestHeader("Idempotency-Key") String key) {
    scope(request);
    return accepted(runs.refreshCalendar(request.projectId(), request.sessionId(), key));
  }

  /** Web APIによる操作で用いるタスク一覧を返す。 */
  @GetMapping("/google-tasks")
  public Text tasks(
      @RequestParam(required = false) Integer priority,
      @RequestParam(required = false) LocalDate dueBefore) {
    return text(TaskCommand.reportList(tasks, priority, dueBefore));
  }

  /** 新たな対象を管理できるよう、タスクを追加する。 */
  @PostMapping("/google-tasks")
  public Text addTask(@RequestBody Task request) {
    if (request == null) throw new IllegalArgumentException("Task required");
    required(request.title());
    return text(
        TaskCommand.reportAdd(
            tasks,
            request.title(),
            request.due(),
            request.priority() == null ? 3 : request.priority()));
  }

  /** Web APIによる操作で用いる完了を返す。 */
  @PostMapping("/google-tasks/{id}/complete")
  public Text done(@PathVariable long id) {
    return text(TaskCommand.reportDone(tasks, id));
  }

  /** 指定された対象を削除する。 */
  @DeleteMapping("/google-tasks/{id}")
  public Text delete(@PathVariable long id) {
    return text(TaskCommand.reportDelete(tasks, id));
  }

  /** Web APIによる操作で用いる権限の確認タスク一覧を返す。 */
  @PostMapping("/google-tasks/authorize")
  public ResponseEntity<ChatResponse> authorizeTasks(
      @RequestBody Scope request, @RequestHeader("Idempotency-Key") String key) {
    scope(request);
    return accepted(runs.authorizeTasks(request.projectId(), request.sessionId(), key));
  }

  /** Web APIによる操作に関する対象範囲を処理する。 */
  private static void scope(Scope request) {
    if (request == null) throw new IllegalArgumentException("Scope required");
    required(request.projectId());
    optional(request.sessionId());
  }

  /** Web APIによる操作に関する必須を処理する。 */
  private static void required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Value required");
    optional(value);
  }

  /** Web APIによる操作に関する任意を処理する。 */
  private static void optional(String value) {
    if (value != null && (value.length() > 16384 || value.indexOf('\0') >= 0))
      throw new IllegalArgumentException("Invalid value");
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private static ResponseEntity<ChatResponse> accepted(AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }
}
