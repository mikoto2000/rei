package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.llm.capture.CaptureStore;
import dev.mikoto2000.rei.llm.capture.LlmCaptureCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 取得記録コマンド一覧をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/llm/capture")
public final class CaptureCommandsController {
  /** 保存先。 */
  private final CaptureStore store;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 会話一覧。 */
  private final SessionLifecycle sessions;

  /** 指定された値や利用先を使って、CaptureCommandsControllerを初期化する。 */
  public CaptureCommandsController(
      CaptureStore store, ProjectRegistry projects, SessionLifecycle sessions) {
    this.store = store;
    this.projects = projects;
    this.sessions = sessions;
  }

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
   * Web APIによる操作で受け渡す承認を表す。
   *
   * @param approved 承認済み
   */
  public record Approval(Boolean approved) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す書き出しを表す。
   *
   * @param path パス
   * @param approved 承認済み
   */
  public record Export(String path, Boolean approved) implements StrictApiRequest {}

  /** Web APIによる操作で用いる次を返す。 */
  @PostMapping("/reservation")
  public Text next(@RequestHeader("X-Rei-Client-Id") String client, @RequestBody Scope scope) {
    var command = scoped(client, scope.projectId(), scope.sessionId());
    return new Text(command.reportNext());
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  @GetMapping("/status")
  public Text status(
      @RequestHeader("X-Rei-Client-Id") String client,
      @RequestParam String projectId,
      @RequestParam String sessionId) {
    return new Text(scoped(client, projectId, sessionId).reportStatus());
  }

  /** Web APIによる操作で用いる無効を返す。 */
  @PostMapping("/reservation/off")
  public Text off(@RequestHeader("X-Rei-Client-Id") String client, @RequestBody Scope scope) {
    return new Text(scoped(client, scope.projectId(), scope.sessionId()).reportOff());
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping("/sessions")
  public Text list() {
    return new Text(command().reportSessions());
  }

  /** Web APIによる操作で用いる試行回数を返す。 */
  @GetMapping("/sessions/{run}/attempts")
  public Text attempts(@PathVariable String run) {
    id(run);
    return new Text(command().reportAttempts(run));
  }

  /** Web APIによる操作で用いる試行を返す。 */
  @GetMapping("/attempts/{attempt}")
  public CaptureStore.AttemptInfo attempt(@PathVariable String attempt) {
    id(attempt);
    return store.attempt(attempt);
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/attempts/{attempt}/show")
  public Text show(@PathVariable String attempt) {
    id(attempt);
    return new Text(command().reportShow(attempt));
  }

  /** Web APIによる操作で用いる元のを返す。 */
  @PostMapping("/attempts/{attempt}/raw")
  public Text raw(@PathVariable String attempt, @RequestBody Approval request) {
    approved(request == null ? null : request.approved());
    id(attempt);
    var command = command();
    command.setConfirmation(prompt -> true);
    return new Text(command.reportRaw(attempt));
  }

  /** Web APIによる操作で用いる書き出しを返す。 */
  @PostMapping("/attempts/{attempt}/export")
  public Text export(@PathVariable String attempt, @RequestBody Export request) {
    approved(request == null ? null : request.approved());
    id(attempt);
    if (request.path() == null
        || request.path().isBlank()
        || request.path().length() > 16384
        || request.path().indexOf('\0') >= 0)
      throw new IllegalArgumentException("Export path required");
    var command = command();
    command.setConfirmation(prompt -> true);
    return new Text(command.reportExport(attempt, request.path()));
  }

  /** 指定された対象を削除する。 */
  @DeleteMapping("/sessions/{run}")
  public Text delete(@PathVariable String run) {
    id(run);
    return new Text(command().reportDelete(run));
  }

  /** 保持している情報を初期状態へ戻すため、取得記録コマンド一覧受付窓口を消去する。 */
  @DeleteMapping("/sessions")
  public Text clear() {
    return new Text(command().reportClear());
  }

  /** Web APIによる操作で用いる対象を限定したを返す。 */
  private LlmCaptureCommand scoped(String client, String project, String session) {
    if (client == null
        || !client.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("Invalid CLI identity");
    projects
        .resolveById(project)
        .orElseThrow(
            () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    sessions.validate(session, project);
    return new LlmCaptureCommand(store, () -> "cli:" + client, () -> session, () -> {});
  }

  /** Web APIによる操作で用いるコマンドを返す。 */
  private LlmCaptureCommand command() {
    return new LlmCaptureCommand(store, () -> null, () -> null, () -> {});
  }

  /** Web APIによる操作に関する承認済みを処理する。 */
  private static void approved(Boolean value) {
    if (!Boolean.TRUE.equals(value))
      throw new IllegalArgumentException("Explicit original-body confirmation required");
  }

  /** Web APIによる操作に関する識別子を処理する。 */
  private static void id(String value) {
    if (value == null || !value.matches("[A-Za-z0-9:_-]{1,512}"))
      throw new IllegalArgumentException("Invalid capture identifier");
  }
}
