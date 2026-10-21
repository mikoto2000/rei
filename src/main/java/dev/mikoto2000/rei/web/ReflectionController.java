package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.reflection.GoalReflectionRepository;
import dev.mikoto2000.rei.reflection.GoalReflectionService;
import dev.mikoto2000.rei.reflection.ReflectionLessonService;
import dev.mikoto2000.rei.reflection.RunReflectionRepository;
import dev.mikoto2000.rei.reflection.RunReflectionService;
import dev.mikoto2000.rei.reflection.VerifiedReflectionMemoryService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 振り返りをWeb APIの操作として受け付ける。
 *
 * <p>Scoped human reflection review. No tool invocation or automatic memory promotion.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/reflections")
public final class ReflectionController {
  /** 保存と検索の窓口。 */
  private final GoalReflectionRepository repository;

  /** サービス。 */
  private final GoalReflectionService service;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 会話一覧。 */
  private final SessionQueryService sessions;

  /** 実行一覧。 */
  private RunReflectionRepository runs;

  /** 実行サービス。 */
  private RunReflectionService runService;

  /** 確認済みの教訓を長期記憶へ反映する窓口。 */
  private VerifiedReflectionMemoryService promotion;

  /** 教訓一覧。 */
  private ReflectionLessonService lessons;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す集約対象を表す。
   *
   * @param id 識別子
   */
  public record Collection(String id) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す対象範囲を表す。
   *
   * @param sessionId 会話の識別子
   */
  public record Scope(String sessionId) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すレビューを表す。
   *
   * @param sessionId 会話の識別子
   * @param id 識別子
   * @param sourceKind 取得元種類
   * @param kind 種類
   * @param revision 改訂番号
   * @param evidenceHash 根拠ハッシュ値
   * @param note 補足
   * @param evidence 根拠
   * @param observation 観測内容
   */
  public record Review(
      String sessionId,
      String id,
      String sourceKind,
      ReflectionLessonService.Kind kind,
      Long revision,
      String evidenceHash,
      String note,
      List<String> evidence,
      String observation)
      implements StrictApiRequest {}

  /** 指定された値や利用先を使って、ReflectionControllerを初期化する。 */
  public ReflectionController(
      GoalReflectionRepository repository,
      GoalReflectionService service,
      ProjectRegistry projects,
      SessionQueryService sessions) {
    this.repository = repository;
    this.service = service;
    this.projects = projects;
    this.sessions = sessions;
  }

  /** Web APIによる操作で使用する実行一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRuns(RunReflectionRepository runs) {
    this.runs = runs;
  }

  /** Web APIによる操作で使用する実行サービスを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRunService(RunReflectionService runService) {
    this.runService = runService;
  }

  /** Web APIによる操作で使用するPromotionを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPromotion(VerifiedReflectionMemoryService promotion) {
    this.promotion = promotion;
  }

  /** Web APIによる操作で使用する教訓一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setLessons(ReflectionLessonService lessons) {
    this.lessons = lessons;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public Text list(@PathVariable String projectId) {
    project(projectId);
    return text(repository.list(projectId));
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public Text show(@PathVariable String projectId, @PathVariable String id) {
    project(projectId);
    return text(repository.get(projectId, id));
  }

  /** 判断に必要な情報を集め、後続の処理へ渡す。 */
  @PostMapping("/collect")
  public Text collect(@PathVariable String projectId, @RequestBody Collection request) {
    project(projectId);
    if (request == null) throw new IllegalArgumentException("Goal identity required");
    return text(service.collect(projectId, identity(request.id())));
  }

  /** Web APIによる操作で用いる実行一覧を返す。 */
  @GetMapping("/runs")
  public Text runs(@PathVariable String projectId) {
    project(projectId);
    return text(required(runs).list(projectId));
  }

  /** Web APIによる操作の操作を実行する。 */
  @GetMapping("/runs/{id}")
  public Text run(@PathVariable String projectId, @PathVariable String id) {
    project(projectId);
    return text(required(runs).get(projectId, id));
  }

  /** Web APIによる操作で用いる収集実行を返す。 */
  @PostMapping("/runs/{id}/collect")
  public Text collectRun(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Scope request) {
    if (request == null) throw new IllegalArgumentException("Session required");
    var owner = owner(projectId, request.sessionId());
    return text(required(runService).collect(projectId, owner.conversationId(), identity(id)));
  }

  /** Web APIによる操作で用いるpromoteを返す。 */
  @PostMapping("/{id}/promote")
  public Text promote(@PathVariable String projectId, @PathVariable String id) {
    project(projectId);
    return text(required(promotion).promote(projectId, identity(id)));
  }

  /** Web APIによる操作で用いる教訓一覧を返す。 */
  @GetMapping("/lessons")
  public Text lessons(@PathVariable String projectId, @RequestParam String sessionId) {
    return text(required(lessons).list(owner(projectId, sessionId)));
  }

  /** Web APIによる操作で用いる教訓を返す。 */
  @GetMapping("/lessons/{id}")
  public Text lesson(
      @PathVariable String projectId, @PathVariable String id, @RequestParam String sessionId) {
    return text(required(lessons).get(owner(projectId, sessionId), identity(id)));
  }

  /** 状況を判断する根拠を得るため、振り返り受付窓口を観測する。 */
  @PostMapping("/lessons/observe")
  public Text observe(@PathVariable String projectId, @RequestBody Review request) {
    return text(
        required(lessons)
            .observe(
                reviewOwner(projectId, request),
                request.sourceKind() == null ? "RUN" : request.sourceKind(),
                identity(request.id())));
  }

  /** 状況を判断する根拠を得るため、補正を観測する。 */
  @PostMapping("/lessons/observe-correction")
  public Text observeCorrection(@PathVariable String projectId, @RequestBody Review request) {
    return text(
        required(lessons)
            .observeCorrection(
                reviewOwner(projectId, request),
                identity(request.id()),
                request.observation(),
                request.note()));
  }

  /** 後続の判断で選べる候補を用意するため、振り返り受付窓口を提案する。 */
  @PostMapping("/lessons/proposals")
  public Text propose(@PathVariable String projectId, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(
        required(lessons)
            .propose(
                owner,
                request.kind() == null
                    ? ReflectionLessonService.Kind.FAILURE_PATTERN
                    : request.kind(),
                request.note(),
                request.evidence()));
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  @PostMapping("/lessons/{id}/validate")
  public Text validate(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(
        required(lessons)
            .validate(
                owner, identity(id), revision(request), request.evidenceHash(), request.note()));
  }

  /** Web APIによる操作で用いるcounterexampleを返す。 */
  @PostMapping("/lessons/{id}/counterexamples")
  public Text counterexample(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(
        required(lessons)
            .counterexample(
                owner, identity(id), revision(request), request.observation(), request.note()));
  }

  /** Web APIによる操作で用いるcorrectを返す。 */
  @PostMapping("/lessons/{id}/corrections")
  public Text correct(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(required(lessons).correct(owner, identity(id), revision(request), request.note()));
  }

  /** Web APIによる操作で用いる記憶の削除を返す。 */
  @PostMapping("/lessons/{id}/forget")
  public Text forget(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(required(lessons).forget(owner, identity(id), revision(request)));
  }

  /** Web APIによる操作で用いるプロジェクトを返す。 */
  private ProjectContext project(String id) {
    return projects.resolveById(id).orElseThrow(() -> new ResourceNotFoundException("Project"));
  }

  /** Web APIによる操作で用いる所有者を返す。 */
  private AgentRunContext owner(String projectId, String sessionId) {
    var project = project(projectId);
    identity(sessionId);
    if (!sessions.getSession(sessionId).projectId().equals(projectId))
      throw new IllegalArgumentException("Session belongs to another Project");
    return new AgentRunContext(
        "human-reflection",
        sessionId,
        project.root(),
        projectId,
        AgentRunContext.RequestSource.WEB);
  }

  /** Web APIによる操作で用いるレビュー所有者を返す。 */
  private AgentRunContext reviewOwner(String projectId, Review request) {
    if (request == null) throw new IllegalArgumentException("Review required");
    return owner(projectId, request.sessionId());
  }

  /** Web APIによる操作で用いる改訂番号を返す。 */
  private static long revision(Review request) {
    return request.revision() == null ? -1 : request.revision();
  }

  /** Web APIによる操作で用いる識別情報を返す。 */
  private static String identity(String id) {
    if (id == null
        || id.isBlank()
        || id.length() > 128
        || id.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Identity required");
    return id;
  }

  /** Web APIによる操作で用いる必須を返す。 */
  private static <T> T required(T service) {
    if (service == null) throw new IllegalStateException("Reflection operation unavailable");
    return service;
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(Object result) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));
  }
}
