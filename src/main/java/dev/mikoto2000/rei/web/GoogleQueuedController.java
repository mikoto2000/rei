package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Google待機中をWeb APIの操作として受け付ける。
 *
 * <p>CLI operations can wait for implicit OAuth without tying up an HTTP request.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/google-operations")
public final class GoogleQueuedController {
  /** 実行一覧。 */
  private final IntegrationRunSubmitService runs;

  /** 指定された値や利用先を使って、GoogleQueuedControllerを初期化する。 */
  public GoogleQueuedController(IntegrationRunSubmitService runs) {
    this.runs = runs;
  }

  /**
   * Web APIによる操作で受け渡す予定表一覧を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param date 日付
   * @param from 起点
   * @param to 終点
   */
  public record CalendarList(
      String projectId, String sessionId, LocalDate date, String from, String to)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す予定表追加を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param title タイトル
   * @param start 開始
   * @param end 終了
   * @param location 場所
   * @param description 説明
   */
  public record CalendarAdd(
      String projectId,
      String sessionId,
      String title,
      String start,
      String end,
      String location,
      String description)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すタスク一覧を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param priority 優先度
   * @param dueBefore 期限変更前
   */
  public record TaskList(String projectId, String sessionId, Integer priority, LocalDate dueBefore)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すタスク追加を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param title タイトル
   * @param due 期限
   * @param priority 優先度
   */
  public record TaskAdd(
      String projectId, String sessionId, String title, LocalDate due, Integer priority)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す対象範囲を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   */
  public record Scope(String projectId, String sessionId) implements StrictApiRequest {}

  /** Web APIによる操作で用いる予定表一覧を返す。 */
  @PostMapping("/calendar/list")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> calendarList(
      @RequestBody CalendarList request) {
    scope(request.projectId(), request.sessionId());
    optional(request.from());
    optional(request.to());
    return accepted(
        runs.listCalendar(
            request.projectId(),
            request.sessionId(),
            request.date(),
            request.from(),
            request.to()));
  }

  /** Web APIによる操作で用いる予定表追加を返す。 */
  @PostMapping("/calendar/add")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> calendarAdd(
      @RequestBody CalendarAdd request) {
    scope(request.projectId(), request.sessionId());
    required(request.title());
    required(request.start());
    required(request.end());
    optional(request.location());
    optional(request.description());
    return accepted(
        runs.addCalendarEvent(
            request.projectId(),
            request.sessionId(),
            request.title(),
            request.start(),
            request.end(),
            request.location(),
            request.description()));
  }

  /** Web APIによる操作で用いるタスク一覧を返す。 */
  @PostMapping("/tasks/list")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> taskList(
      @RequestBody TaskList request) {
    scope(request.projectId(), request.sessionId());
    return accepted(
        runs.listGoogleTasks(
            request.projectId(), request.sessionId(), request.priority(), request.dueBefore()));
  }

  /** Web APIによる操作で用いるタスク追加を返す。 */
  @PostMapping("/tasks/add")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> taskAdd(
      @RequestBody TaskAdd request) {
    scope(request.projectId(), request.sessionId());
    required(request.title());
    return accepted(
        runs.addGoogleTask(
            request.projectId(),
            request.sessionId(),
            request.title(),
            request.due(),
            request.priority() == null ? 3 : request.priority()));
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  @PostMapping("/tasks/{id}/complete")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> complete(
      @PathVariable long id, @RequestBody Scope request) {
    scope(request.projectId(), request.sessionId());
    return accepted(runs.completeGoogleTask(request.projectId(), request.sessionId(), id));
  }

  /** 指定された対象を削除する。 */
  @PostMapping("/tasks/{id}/delete")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> delete(
      @PathVariable long id, @RequestBody Scope request) {
    scope(request.projectId(), request.sessionId());
    return accepted(runs.deleteGoogleTask(request.projectId(), request.sessionId(), id));
  }

  /** Web APIによる操作に関する対象範囲を処理する。 */
  private static void scope(String project, String session) {
    required(project);
    optional(session);
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

  /** Web APIによる操作で用いる受付済みを返す。 */
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
