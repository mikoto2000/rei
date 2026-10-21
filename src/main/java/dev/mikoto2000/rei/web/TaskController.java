package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.HistoryPage;
import dev.mikoto2000.rei.application.task.TaskControlService;
import dev.mikoto2000.rei.application.task.TaskManagerService;
import dev.mikoto2000.rei.application.task.TaskView;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** タスクをWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.task-manager.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/tasks")
public class TaskController {
  /** タスク一覧。 */
  private final TaskManagerService tasks;

  /** タスクの状態変更を受け付ける窓口。 */
  private final TaskControlService controls;

  /** 指定された値や利用先を使って、TaskControllerを初期化する。 */
  public TaskController(TaskManagerService tasks, TaskControlService controls) {
    this.tasks = tasks;
    this.controls = controls;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public HistoryPage<TaskView> list(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return tasks.list(projectId, sessionId, limit, cursor);
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  @GetMapping("/{id}")
  public TaskView get(
      @PathVariable String id,
      @RequestParam String projectId,
      @RequestParam(required = false) String sessionId) {
    return tasks.get(projectId, sessionId, id);
  }

  /**
   * Web APIによる操作で受け渡す受付要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param message メッセージ
   * @param mode 動作モード
   */
  public record SubmitRequest(
      String projectId, String sessionId, String message, AgentRunContext.Mode mode)
      implements StrictApiRequest {}

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  @PostMapping
  public ResponseEntity<TaskView> submit(@RequestBody SubmitRequest request) {
    var task =
        controls.submit(
            request.projectId(), request.sessionId(), request.message(), request.mode());
    var location =
        UriComponentsBuilder.fromPath("/api/v1/tasks/{id}")
            .queryParam("projectId", task.projectId())
            .queryParamIfPresent("sessionId", Optional.ofNullable(task.sessionId()))
            .buildAndExpand(task.id())
            .encode()
            .toUri();
    return ResponseEntity.accepted().location(location).body(task);
  }

  /**
   * Web APIによる操作で受け渡す制御要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param expectedRunId 期待する内容実行の識別子
   * @param expectedRevision 期待する内容改訂番号
   */
  public record ControlRequest(
      String projectId, String sessionId, String expectedRunId, Long expectedRevision)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す入力要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param expectedRunId 期待する内容実行の識別子
   * @param expectedRevision 期待する内容改訂番号
   * @param message メッセージ
   */
  public record InputRequest(
      String projectId,
      String sessionId,
      String expectedRunId,
      Long expectedRevision,
      String message)
      implements StrictApiRequest {}

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/{id}/cancel")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView cancel(@PathVariable String id, @RequestBody ControlRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.cancel(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision());
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  @PostMapping("/{id}/resume")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView resume(@PathVariable String id, @RequestBody ControlRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.resume(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision());
  }

  /** Web APIによる操作で用いる中断を返す。 */
  @PostMapping("/{id}/suspend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView suspend(@PathVariable String id, @RequestBody ControlRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.suspend(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision());
  }

  /** Web APIによる操作で用いる入力を返す。 */
  @PostMapping("/{id}/input")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public TaskView input(@PathVariable String id, @RequestBody InputRequest request) {
    required(request.projectId(), request.expectedRevision());
    return controls.input(
        request.projectId(),
        request.sessionId(),
        id,
        request.expectedRunId(),
        request.expectedRevision(),
        request.message());
  }

  /** Web APIによる操作に関する必須を処理する。 */
  private static void required(String project, Long revision) {
    if (project == null
        || project.isBlank()
        || project.length() > 128
        || revision == null
        || revision < 0)
      throw new IllegalArgumentException("Project and expected Task revision required");
  }
}
