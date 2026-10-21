package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionQueryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会話をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/sessions")
public class SessionController {
  /** 会話一覧。 */
  private final SessionQueryService sessions;

  /** 受付から終了までの管理。 */
  private dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle;

  /** プロジェクト一覧。 */
  private dev.mikoto2000.rei.core.project.ProjectRegistry projects;

  /** Web APIによる操作で使用するプロジェクト一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setProjects(dev.mikoto2000.rei.core.project.ProjectRegistry projects) {
    this.projects = projects;
  }

  /** Web APIによる操作で使用する受付から終了までの管理を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setLifecycle(dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle) {
    this.lifecycle = lifecycle;
  }

  /** 指定された値や利用先を使って、SessionControllerを初期化する。 */
  public SessionController(SessionQueryService sessions) {
    this.sessions = sessions;
  }

  /**
   * Web APIによる操作で受け渡す作成要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param title タイトル
   */
  public record CreateRequest(String projectId, String title) implements StrictApiRequest {}

  /** 指定された内容から新しい対象を作成する。 */
  @PostMapping
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  public SessionResponse create(@RequestBody CreateRequest request) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (request == null || request.title() != null && request.title().length() > 2000)
      throw new IllegalArgumentException("Invalid Session request");
    return SessionResponse.from(lifecycle().create(project(request.projectId()), request.title()));
  }

  /**
   * Web APIによる操作で受け渡す形式要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param style 形式
   * @param voiceOnly 音声入力だけに適用するかどうか
   */
  public record StyleRequest(
      String projectId, dev.mikoto2000.rei.core.chat.ResponseStyle style, Boolean voiceOnly)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す形式応答を表す。
   *
   * @param sessionId 会話の識別子
   * @param projectId 所属プロジェクトの識別子
   * @param style 形式
   * @param voiceOnly 音声入力だけに適用するかどうか
   */
  public record StyleResponse(
      String sessionId,
      String projectId,
      dev.mikoto2000.rei.core.chat.ResponseStyle style,
      boolean voiceOnly) {
    /** Web APIによる操作で利用する情報を、受け取った形式から変換する。 */
    static StyleResponse from(dev.mikoto2000.rei.application.session.SessionMetadata value) {
      return new StyleResponse(
          value.sessionId(), value.projectId(), value.responseStyle(), value.voiceOnly());
    }
  }

  /** Web APIによる操作で用いる形式を返す。 */
  @GetMapping("/{sessionId}/response-style")
  public StyleResponse style(@PathVariable String sessionId, @RequestParam String projectId) {
    return StyleResponse.from(lifecycle().validate(sessionId, project(projectId).id()));
  }

  /** Web APIによる操作で用いる形式を返す。 */
  @PatchMapping("/{sessionId}/response-style")
  public StyleResponse style(@PathVariable String sessionId, @RequestBody StyleRequest request) {
    if (request == null
        || request.style() == null
        || Boolean.TRUE.equals(request.voiceOnly())
            && request.style() != dev.mikoto2000.rei.core.chat.ResponseStyle.CONVERSATION)
      throw new IllegalArgumentException("Invalid response style");
    return StyleResponse.from(
        lifecycle()
            .responseStyle(
                project(request.projectId()),
                sessionId,
                request.style(),
                Boolean.TRUE.equals(request.voiceOnly())));
  }

  /** Web APIによる操作で用いる受付から終了までの管理を返す。 */
  private dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle() {
    if (lifecycle == null) throw new IllegalStateException("Session lifecycle unavailable");
    return lifecycle;
  }

  /** Web APIによる操作で用いるプロジェクトを返す。 */
  private dev.mikoto2000.rei.core.project.ProjectContext project(String id) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (id == null || id.isBlank() || id.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    if (projects == null) throw new IllegalStateException("Project registry unavailable");
    return projects
        .resolveById(id)
        .orElseThrow(
            () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public SessionListResponse list(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    var page = sessions.listSessions(projectId, limit, cursor);
    return new SessionListResponse(
        page.items().stream().map(SessionResponse::from).toList(), page.nextCursor());
  }

  /** Web APIによる操作で用いる詳細を返す。 */
  @GetMapping("/{sessionId}")
  public SessionResponse detail(@PathVariable String sessionId) {
    return SessionResponse.from(sessions.getSession(sessionId));
  }

  /**
   * Web APIによる操作で受け渡す終了要求を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   */
  public record EndRequest(String projectId) {}

  /** Web APIによる操作で用いる終了を返す。 */
  @PostMapping("/{sessionId}/end")
  public SessionResponse end(@PathVariable String sessionId, @RequestBody EndRequest request) {
    if (request == null || request.projectId() == null || request.projectId().isBlank())
      throw new IllegalArgumentException("Project ID is required");
    if (lifecycle == null) throw new IllegalStateException("Session lifecycle unavailable");
    return SessionResponse.from(lifecycle.end(sessionId, request.projectId()));
  }

  /** Web APIによる操作で用いるやり取り一覧を返す。 */
  @GetMapping("/{sessionId}/turns")
  public SessionTurnListResponse turns(
      @PathVariable String sessionId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    var page = sessions.listTurns(sessionId, limit, cursor);
    return new SessionTurnListResponse(
        sessionId,
        page.items().stream().map(SessionTurnResponse::from).toList(),
        page.nextCursor());
  }
}
