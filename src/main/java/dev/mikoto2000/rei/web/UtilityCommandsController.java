package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.reminder.ReminderService;
import dev.mikoto2000.rei.reminder.command.ReminderCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 補助コマンド一覧をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1")
public final class UtilityCommandsController {
  /** リマインダー一覧。 */
  private final ReminderService reminders;

  /** 実行一覧。 */
  private final IntegrationRunSubmitService runs;

  /** 指定された値や利用先を使って、UtilityCommandsControllerを初期化する。 */
  public UtilityCommandsController(ReminderService reminders, IntegrationRunSubmitService runs) {
    this.reminders = reminders;
    this.runs = runs;
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡すリマインダーを表す。
   *
   * @param message メッセージ
   * @param at 日時
   * @param target 対象
   * @param minutesBefore 分数変更前
   */
  public record Reminder(String message, String at, String target, Integer minutesBefore)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す対象範囲を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   */
  public record Scope(String projectId, String sessionId) implements StrictApiRequest {}

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping("/reminders/commands")
  public Text list() {
    return text(ReminderCommand.reportList(reminders));
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @PostMapping("/reminders/commands")
  public Text add(@RequestBody Reminder request) {
    if (request == null
        || request.message() == null
        || request.message().isBlank()
        || request.message().length() > 16384
        || request.message().indexOf('\0') >= 0)
      throw new IllegalArgumentException("Reminder required");
    return text(
        ReminderCommand.reportAdd(
            reminders,
            request.message(),
            request.at() == null ? null : java.time.OffsetDateTime.parse(request.at()),
            request.target() == null ? null : java.time.OffsetDateTime.parse(request.target()),
            request.minutesBefore()));
  }

  /** 指定された対象を削除する。 */
  @DeleteMapping("/reminders/commands/{id}")
  public Text delete(@PathVariable long id) {
    return text(ReminderCommand.reportDelete(reminders, id));
  }

  /** Web APIによる操作で用いる日次案内を返す。 */
  @PostMapping("/briefing/commands/today")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> briefing(
      @RequestBody Scope request) {
    scope(request);
    return accepted(runs.briefing(request.projectId(), request.sessionId()));
  }

  /** Web APIによる操作で用いる作業を返す。 */
  @PostMapping("/projects/{projectId}/work-context/updates")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> work(
      @PathVariable String projectId, @RequestBody Scope request) {
    scope(request);
    if (!projectId.equals(request.projectId()))
      throw new IllegalArgumentException("Project ownership mismatch");
    return accepted(runs.updateWork(projectId, request.sessionId()));
  }

  /** Web APIによる操作に関する対象範囲を処理する。 */
  private static void scope(Scope request) {
    if (request == null || request.projectId() == null || request.projectId().isBlank())
      throw new IllegalArgumentException("Project required");
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
