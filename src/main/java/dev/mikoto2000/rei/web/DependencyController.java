package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.core.dependency.DependencyObservationService;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 依存条件をWeb APIの操作として受け付ける。
 *
 * <p>Human answers are saved facts, never permission grants or execution requests.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/dependencies")
public class DependencyController {
  /** 保存と検索の窓口。 */
  private final PersistentDependencyRepository repository;

  /** 観測内容一覧。 */
  private final DependencyObservationService observations;

  /** プロジェクト一覧。 */
  private final dev.mikoto2000.rei.core.project.ProjectRegistry projects;

  /** 利用条件の確認。 */
  private final dev.mikoto2000.rei.core.policy.ToolPermissionGuard guard;

  /** Web APIによる操作を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DependencyController(
      PersistentDependencyRepository repository, DependencyObservationService observations) {
    this(repository, observations, null, null);
  }

  /** 指定された値や利用先を使って、DependencyControllerを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public DependencyController(
      PersistentDependencyRepository repository,
      DependencyObservationService observations,
      dev.mikoto2000.rei.core.project.ProjectRegistry projects,
      dev.mikoto2000.rei.core.policy.ToolPermissionGuard guard) {
    this.repository = repository;
    this.observations = observations;
    this.projects = projects;
    this.guard = guard;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public List<PersistentDependencyRepository.Entry> list(@PathVariable String projectId) {
    return repository.list(projectId);
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public PersistentDependencyRepository.Entry show(
      @PathVariable String projectId, @PathVariable String id) {
    return repository.get(projectId, id);
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  @GetMapping("/{id}/history")
  public List<PersistentDependencyRepository.Fact> history(
      @PathVariable String projectId, @PathVariable String id) {
    return repository.history(projectId, id);
  }

  /**
   * Web APIによる操作で受け渡す回答要求を表す。
   *
   * @param expectedVersion 期待する内容形式のバージョン
   * @param answer 回答
   */
  public record AnswerRequest(Long expectedVersion, String answer) {}

  /**
   * Web APIによる操作で受け渡す利用者回答を表す。
   *
   * @param value 値
   */
  public record HumanAnswer(String value) implements StrictApiRequest {}

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  @PostMapping("/{id}/check")
  public PersistentDependencyRepository.Entry check(
      @PathVariable String projectId, @PathVariable String id) {
    var project = project(projectId);
    var entry = repository.get(projectId, id);
    boolean network = entry.spec().network();
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            entry.id(),
            entry.sessionId(),
            project.root(),
            projectId,
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB);
    guard.check(
        network ? "checkHttpDependency" : "checkDependency",
        "{\"dependencyId\":\"" + entry.id() + "\"}",
        owner);
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
      var result = observations.inspect(projectId, id, network);
      observations.flushFacts();
      return result;
    }
  }

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/{id}/cancel")
  public PersistentDependencyRepository.Entry cancel(
      @PathVariable String projectId, @PathVariable String id) {
    project(projectId);
    var entry = repository.get(projectId, id);
    var result = repository.cancel(projectId, id, entry.version());
    observations.flushFacts();
    return result;
  }

  /** Web APIによる操作で用いる利用者回答を返す。 */
  @PostMapping("/{id}/human-answer")
  public PersistentDependencyRepository.Entry humanAnswer(
      @PathVariable String projectId, @PathVariable String id, @RequestBody HumanAnswer request) {
    project(projectId);
    if (request == null) throw new IllegalArgumentException("Human answer required");
    var entry = repository.get(projectId, id);
    repository.answer(projectId, id, request.value(), entry.version());
    var result = observations.inspect(projectId, id, false);
    observations.flushFacts();
    return result;
  }

  /** Web APIによる操作で用いるプロジェクトを返す。 */
  private dev.mikoto2000.rei.core.project.ProjectContext project(String id) {
    if (projects == null || guard == null)
      throw new IllegalStateException("Dependency controls unavailable");
    return projects
        .resolveById(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown registered Project"));
  }

  /** Web APIによる操作で用いる回答を返す。 */
  @PostMapping("/{id}/answer")
  public PersistentDependencyRepository.Entry answer(
      @PathVariable String projectId, @PathVariable String id, @RequestBody AnswerRequest request) {
    if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0)
      throw new IllegalArgumentException("Version required");
    if (PersistentDependencyRepository.terminal(repository.get(projectId, id).state()))
      throw new OperationConflictException();
    var result = repository.answer(projectId, id, request.answer(), request.expectedVersion());
    observations.flushFacts();
    return result;
  }
}
