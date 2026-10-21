package dev.mikoto2000.rei.core.dependency;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 依存条件をモデルから呼び出せるツールとして提供する。 */
@Component
public class DependencyTools {
  /** リポジトリ。 */
  private final PersistentDependencyRepository repo;

  /** サービス。 */
  private final DependencyObservationService service;

  /** 取得元。 */
  private final DependencySourceProbe source;

  /** HTTP通信Awaiter。 */
  private final DependencyAwaiter httpAwaiter =
      new DependencyAwaiter(
          dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(),
          Thread::sleep,
          Duration.ofSeconds(1));

  /** 依存条件が満たされるまでの待機を管理する窓口。 */
  private final DependencyAwaiter awaiter =
      new DependencyAwaiter(
          dev.mikoto2000.rei.temporal.MonotonicTimeSource.system(), Thread::sleep);

  /** 指定された値や利用先を使って、DependencyToolsを初期化する。 */
  public DependencyTools(
      PersistentDependencyRepository repo,
      DependencyObservationService service,
      DependencySourceProbe source) {
    this.repo = repo;
    this.service = service;
    this.source = source;
  }

  /** 後から対象を参照できるよう、依存条件を登録する。 */
  @Tool(
      name = "registerDependency",
      description =
          "Persist a scoped dependency: FILE_EXISTS, FILE_SHA256, FILE_JSON_PREDICATE (expected version 1 declarative JSON predicate; administrator opt-in), FILE_CHANGED (baseline captured if absent), GIT_STATE_CHANGED (target HEAD, baseline captured if absent), PROCESS_EXIT (expected exit 0 by default), HTTP_STATUS (expected 100..599), HTTP_BODY_SHA256 (expected status:64-hex SHA-256, max body 64KiB), HTTP_JSON_VALUE (expected JSON {status,pointer,value:scalar} or {status,predicate:{version:1,expression:...}} with administrator predicate opt-in), USER_ANSWER (target question), GITHUB_PR_MERGED (target owner/repo#PR, an authenticated saved local webhook fact; no network request). Optional ISO-8601 lifetime defaults PT1H, max 366 days; at most 16 existing same-session prerequisites. Automatic watching requires administrator enablement and auto permission; registration does not execute an action.")
  public PersistentDependencyRepository.Entry registerDependency(
      String kind,
      String target,
      @ToolParam(required = false) String expected,
      @ToolParam(required = false) String lifetime,
      @ToolParam(required = false) List<String> prerequisites) {
    var owner = AgentRunScope.current();
    if (owner == null || owner.projectId() == null)
      throw new IllegalArgumentException("Owning Project/Session required");
    if (kind == null || kind.isBlank())
      throw new IllegalArgumentException("Dependency kind required");
    var type = DependencySpec.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
    if (type == DependencySpec.Kind.EXTERNAL_AGENT)
      throw new IllegalArgumentException(
          "External dependencies are created only by askExternalAgent");
    if (type == DependencySpec.Kind.GIT_STATE_CHANGED && expected == null)
      expected = source.gitBaseline(owner.projectRoot());
    if (type == DependencySpec.Kind.FILE_CHANGED && expected == null)
      expected = source.fileBaseline(owner.projectRoot(), target);
    var entry =
        repo.create(
            owner,
            new DependencySpec(type, target, expected),
            lifetime == null ? Duration.ofHours(1) : Duration.parse(lifetime),
            prerequisites);
    service.flushFacts();
    return entry;
  }

  /** 外部条件の待機で用いる自分が所有する対象を返す。 */
  private PersistentDependencyRepository.Entry owned(String id) {
    var owner = AgentRunScope.current();
    if (owner == null || owner.projectId() == null)
      throw new IllegalArgumentException("Owning Project/Session required");
    var entry = repo.get(owner.projectId(), id);
    if (!entry.sessionId().equals(owner.conversationId())
        || !entry.projectRoot().equals(owner.projectRoot().toString()))
      throw new IllegalArgumentException("Dependency belongs to another Session/location");
    return entry;
  }

  /** 外部条件の待機で用いる依存条件状態を返す。 */
  @Tool(
      name = "dependencyStatus",
      description =
          "Read a saved dependency state without observing external sources. Only current Project/Session.")
  public PersistentDependencyRepository.Entry dependencyStatus(String dependencyId) {
    return owned(dependencyId);
  }

  /** 外部条件の待機で用いる依存条件履歴を返す。 */
  @Tool(
      name = "dependencyHistory",
      description =
          "Read bounded saved observation facts for a dependency in the current Project/Session.")
  public List<PersistentDependencyRepository.Fact> dependencyHistory(String dependencyId) {
    var entry = owned(dependencyId);
    return repo.history(entry.projectId(), entry.id());
  }

  /** 後続の操作を進められるか判断するため、依存条件を確認する。 */
  @Tool(
      name = "checkDependency",
      description =
          "Observe a saved file/Git/managed-process/user-answer dependency once. No HTTP requests; use checkHttpDependency for HTTP.")
  public PersistentDependencyRepository.Entry checkDependency(String dependencyId) {
    var entry = owned(dependencyId);
    return service.inspect(entry.projectId(), entry.id(), false);
  }

  /** 後続の操作を進められるか判断するため、HTTP通信依存条件を確認する。 */
  @Tool(
      name = "checkHttpDependency",
      description =
          "Perform one bounded network observation of a saved HTTP_STATUS, HTTP_BODY_SHA256 or HTTP_JSON_VALUE dependency. No redirects, credentials or response-body exposure. Only current Project/Session.")
  public PersistentDependencyRepository.Entry checkHttpDependency(String dependencyId) {
    var entry = owned(dependencyId);
    if (!entry.spec().network()) throw new IllegalArgumentException("HTTP dependency required");
    return service.inspect(entry.projectId(), entry.id(), true);
  }

  /** 必要な状態が整ってから先へ進むため、対象依存条件を待つ。 */
  @Tool(
      name = "waitForDependency",
      description =
          "Bounded wait (default 10s, max 60s) for a non-HTTP saved dependency. WAITING at timeout is not completion. Cancellation and waiting integrate with the current Run; does not launch/kill processes or answer questions. User-answer completion means a reply was received; read its saved answer before deciding what to do.")
  public DependencyObservation waitForDependency(
      String dependencyId,
      @ToolParam(required = false) Integer timeoutSeconds,
      ToolContext toolContext) {
    return waitFor(dependencyId, timeoutSeconds, toolContext, false);
  }

  /** 必要な状態が整ってから先へ進むため、対象HTTP通信依存条件を待つ。 */
  @Tool(
      name = "waitForHttpDependency",
      description =
          "Bounded network wait (default 10s, max 60s) for a saved HTTP_STATUS, HTTP_BODY_SHA256 or HTTP_JSON_VALUE dependency. Each request bounded to 2s; polls at least 1s apart, no redirects or body exposure. WAITING is not completion.")
  public DependencyObservation waitForHttpDependency(
      String dependencyId,
      @ToolParam(required = false) Integer timeoutSeconds,
      ToolContext toolContext) {
    return waitFor(dependencyId, timeoutSeconds, toolContext, true);
  }

  /** 必要な状態が整ってから先へ進むため、対象を待つ。 */
  private DependencyObservation waitFor(
      String id, Integer seconds, ToolContext context, boolean network) {
    var entry = owned(id);
    if (network != (entry.spec().network()))
      throw new IllegalArgumentException("Use the matching network/non-network wait tool");
    var execution =
        context != null
                && context.getContext().get(RunExecutionContext.KEY)
                    instanceof RunExecutionContext current
            ? current
            : null;
    var observation =
        (network ? httpAwaiter : awaiter)
            .await(
                () -> {
                  var observed = service.inspect(entry.projectId(), entry.id(), network);
                  return new DependencyObservation(
                      observed.id(), observed.state(), observed.reason());
                },
                Duration.ofSeconds(seconds == null ? 10 : seconds),
                () -> {
                  if (execution != null) execution.checkActive();
                });
    if (execution != null) execution.observeDependency(observation);
    return observation;
  }

  /** 利用者が不要とした実行を止めるため、依存条件をキャンセルする。 */
  @Tool(
      name = "cancelDependency",
      description =
          "Cancel watching a saved dependency in the current Project/Session. Never kills its process or invokes an external action. Terminal observations cannot be overwritten.")
  public PersistentDependencyRepository.Entry cancelDependency(String dependencyId) {
    var entry = owned(dependencyId);
    var cancelled = repo.cancel(entry.projectId(), entry.id());
    service.flushFacts();
    return cancelled;
  }
}
