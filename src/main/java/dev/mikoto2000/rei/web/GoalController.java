package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.goal.GoalCompletionGate;
import dev.mikoto2000.rei.goal.GoalCompletionProgress;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.goal.GoalWaitRepository;
import dev.mikoto2000.rei.goal.GoalWaitService;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 目標をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/goals")
public class GoalController {
  /** 保存と検索の窓口。 */
  private final GoalRepository repository;

  /** 繰り返し。 */
  private final GoalLoopService loop;

  /** 指定された値や利用先を使って、GoalControllerを初期化する。 */
  public GoalController(GoalRepository repository, GoalLoopService loop) {
    this.repository = repository;
    this.loop = loop;
  }

  /** プロジェクト一覧。 */
  private dev.mikoto2000.rei.core.project.ProjectRegistry projects;

  /** 会話一覧。 */
  private dev.mikoto2000.rei.application.session.SessionLifecycle sessions;

  /** Web APIによる操作に関する設定Creationを処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureCreation(
      dev.mikoto2000.rei.core.project.ProjectRegistry projects,
      dev.mikoto2000.rei.application.session.SessionLifecycle sessions) {
    this.projects = projects;
    this.sessions = sessions;
  }

  /**
   * Web APIによる操作で受け渡す作成要求を表す。
   *
   * @param sessionId 会話の識別子
   * @param objective 目的
   * @param file ファイル
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param criteriaJson 判断基準一覧JSON変換
   * @param completionJson 完了条件JSON変換
   * @param maxRuns 最大実行一覧
   * @param maxLlmCalls 最大言語モデル呼び出し回数
   */
  public record CreateRequest(
      String sessionId,
      String objective,
      String file,
      String sha256,
      String criteriaJson,
      String completionJson,
      Integer maxRuns,
      Integer maxLlmCalls)
      implements StrictApiRequest {}

  /** 指定された内容から新しい対象を作成する。 */
  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public GoalRepository.Goal create(
      @PathVariable String projectId, @RequestBody CreateRequest request) {
    if (request == null
        || request.sessionId() == null
        || request.sessionId().isBlank()
        || request.sessionId().length() > 256
        || request.objective() == null
        || request.objective().isBlank()
        || request.objective().length() > 4096
        || request.criteriaJson() != null && (request.file() != null || request.sha256() != null)
        || request.completionJson() != null && request.completionJson().length() > 32768)
      throw new IllegalArgumentException("Invalid Goal creation request");
    int runs = request.maxRuns() == null ? 3 : request.maxRuns(),
        calls = request.maxLlmCalls() == null ? 20 : request.maxLlmCalls();
    if (runs < 1 || runs > 10 || calls < 1 || calls > 100)
      throw new IllegalArgumentException("Goal budgets: 1..10 Runs and 1..100 LLM calls");
    if (projects == null || sessions == null)
      throw new IllegalStateException("Goal creation unavailable");
    var project =
        projects
            .resolveById(projectId)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    sessions.validate(request.sessionId(), project.id());
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            java.util.UUID.randomUUID().toString(),
            request.sessionId(),
            project.root(),
            project.id(),
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB);
    if (request.criteriaJson() == null && request.completionJson() == null)
      return control(
          () ->
              loop.create(
                  owner, request.objective(), request.file(), request.sha256(), runs, calls));
    var criteria =
        request.criteriaJson() == null
            ? List.of(new GoalRepository.FileCriterion(request.file(), request.sha256()))
            : GoalRepository.parseCriteria(request.criteriaJson());
    if (request.completionJson() == null)
      return control(() -> loop.create(owner, request.objective(), criteria, runs, calls));
    var definition = GoalRepository.parseCompletion(request.completionJson());
    return control(
        () -> loop.create(owner, request.objective(), criteria, definition, runs, calls));
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public List<GoalRepository.Goal> list(@PathVariable String projectId) {
    return repository.list(projectId);
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public GoalRepository.Goal show(@PathVariable String projectId, @PathVariable String id) {
    return repository.get(projectId, id);
  }

  /** Web APIによる操作で用いる進捗を返す。 */
  @GetMapping("/{id}/completion-progress")
  public GoalCompletionProgress progress(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.progress(projectId, id));
  }

  /** Web APIによる操作で用いる段階一覧を返す。 */
  @GetMapping("/{id}/stages")
  public dev.mikoto2000.rei.goal.GoalStageService.Progress stages(
      @PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.stageProgress(projectId, id));
  }

  /**
   * Web APIによる操作で受け渡す段階計画要求を表す。
   *
   * @param expectedPlanVersion 期待する内容計画形式のバージョン
   * @param stages 段階一覧
   */
  public record StagePlanRequest(
      long expectedPlanVersion, List<dev.mikoto2000.rei.goal.GoalStageDefinition> stages)
      implements StrictApiRequest {
    /** 受け取った情報からStagePlanRequestを作成する。 */
    public StagePlanRequest {
      stages = stages == null ? null : List.copyOf(stages);
    }
  }

  /** Web APIによる操作で用いる定義段階一覧を返す。 */
  @PutMapping("/{id}/stages")
  public List<dev.mikoto2000.rei.goal.GoalStageRepository.Stage> defineStages(
      @PathVariable String projectId,
      @PathVariable String id,
      @RequestBody StagePlanRequest request) {
    if (request == null || request.expectedPlanVersion() < 0 || request.stages() == null)
      throw new IllegalArgumentException("Stage plan and observed version required");
    return control(
        () -> loop.defineStages(projectId, id, request.expectedPlanVersion(), request.stages()));
  }

  /**
   * Web APIによる操作で受け渡す段階根拠要求を表す。
   *
   * @param expectedVersion 期待する内容形式のバージョン
   * @param proof 確認の根拠
   */
  public record StageEvidenceRequest(long expectedVersion, GoalCompletionGate.Proof proof)
      implements StrictApiRequest {}

  /** Web APIによる操作で用いる段階根拠を返す。 */
  @PostMapping("/{id}/stages/{stageId}/evidence")
  public dev.mikoto2000.rei.goal.GoalStageService.Progress stageEvidence(
      @PathVariable String projectId,
      @PathVariable String id,
      @PathVariable String stageId,
      @RequestBody StageEvidenceRequest request) {
    if (request == null || request.expectedVersion() < 1 || request.proof() == null)
      throw new IllegalArgumentException("Stage proof and observed version required");
    return control(
        () ->
            loop.attachStageEvidence(
                projectId, id, stageId, request.expectedVersion(), request.proof()));
  }

  /**
   * Web APIによる操作で受け渡す履歴を表す。
   *
   * @param history 履歴
   * @param attempts 試行回数
   */
  public record History(
      List<GoalRepository.History> history, List<GoalRepository.Attempt> attempts) {}

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  @GetMapping("/{id}/history")
  public History history(@PathVariable String projectId, @PathVariable String id) {
    return new History(repository.history(projectId, id), repository.attempts(projectId, id));
  }

  /** 要求された条件を満たすことを確認する。 */
  @PostMapping("/{id}/verify")
  public GoalLoopService.Inspection verify(
      @PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.verify(projectId, id));
  }

  /** Web APIによる操作で用いる定義完了条件JSON変換を返す。 */
  @PutMapping("/{id}/completion")
  public GoalRepository.Goal defineCompletionJson(
      @PathVariable String projectId, @PathVariable String id, @RequestBody String json) {
    return defineCompletion(projectId, id, GoalRepository.parseCompletion(json));
  }

  /** Web APIによる操作で用いる定義完了条件を返す。 */
  public GoalRepository.Goal defineCompletion(
      String projectId, String id, GoalCompletionGate.Definition definition) {
    return control(() -> loop.defineCompletion(projectId, id, definition));
  }

  /** Web APIによる操作で用いる関連付け完了条件JSON変換を返す。 */
  @PostMapping("/{id}/completion-evidence")
  public GoalRepository.Goal attachCompletionJson(
      @PathVariable String projectId, @PathVariable String id, @RequestBody String json) {
    return attachCompletion(projectId, id, GoalRepository.parseCompletionProof(json));
  }

  /** Web APIによる操作で用いる関連付け完了条件を返す。 */
  public GoalRepository.Goal attachCompletion(
      String projectId, String id, GoalCompletionGate.Proof proof) {
    return control(() -> loop.attachCompletion(projectId, id, proof));
  }

  /** Web APIによる操作の操作を実行する。 */
  @PostMapping("/{id}/run")
  public GoalRepository.Goal run(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.run(projectId, id));
  }

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/{id}/cancel")
  public GoalRepository.Goal cancel(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.cancel(projectId, id));
  }

  /**
   * Web APIによる操作で受け渡す状態の照合要求を表す。
   *
   * @param expectedRunId 期待する内容実行の識別子
   * @param acknowledgeUncertainSideEffects acknowledge不確実なSideEffects
   */
  public record ReconcileRequest(String expectedRunId, boolean acknowledgeUncertainSideEffects) {}

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  @PostMapping("/{id}/reconcile")
  public GoalRepository.Goal reconcile(
      @PathVariable String projectId,
      @PathVariable String id,
      @RequestBody ReconcileRequest request) {
    if (request == null
        || !request.acknowledgeUncertainSideEffects()
        || request.expectedRunId() == null
        || request.expectedRunId().isBlank())
      throw new IllegalArgumentException(
          "Inspect effects and acknowledge the exact saved Run before reconciliation");
    return control(
        () ->
            loop.reconcile(
                projectId,
                id,
                request.expectedRunId().equals("none") ? null : request.expectedRunId()));
  }

  /** 待機一覧。 */
  private GoalWaitService waits;

  /** Web APIによる操作に関する設定待機一覧を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureWaits(GoalWaitService waits) {
    this.waits = waits;
  }

  /** Web APIによる操作で用いる待機一覧を返す。 */
  private GoalWaitService waits() {
    if (waits == null) throw new IllegalStateException("Goal waiting unavailable");
    return waits;
  }

  /**
   * Web APIによる操作で受け渡す待機要求を表す。
   *
   * @param dependencyId 依存条件識別子
   * @param reason 理由
   * @param sessionId 会話の識別子
   */
  public record WaitRequest(String dependencyId, String reason, String sessionId)
      implements StrictApiRequest {
    /** Web APIによる操作を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public WaitRequest(String dependencyId, String reason) {
      this(dependencyId, reason, null);
    }
  }

  /**
   * Web APIによる操作で受け渡す待機形式のバージョンを表す。
   *
   * @param expectedVersion 期待する内容形式のバージョン
   */
  public record WaitVersion(long expectedVersion) {
    /** 受け取った情報からWaitVersionを作成する。 */
    public WaitVersion {
      if (expectedVersion < 1) throw new IllegalArgumentException("Positive wait version required");
    }
  }

  /** 必要な状態が整ってから先へ進むため、対象を待つ。 */
  @PostMapping("/{id}/wait")
  public GoalWaitRepository.Wait waitFor(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitRequest request) {
    if (request == null) throw new IllegalArgumentException("Dependency and reason required");
    if (request.sessionId() != null
        && !request.sessionId().equals(repository.get(projectId, id).sessionId()))
      throw new IllegalArgumentException("Select the owning Goal Session");
    return control(() -> waits().waitFor(projectId, id, request.dependencyId(), request.reason()));
  }

  /** Web APIによる操作で用いる待機中を返す。 */
  @GetMapping("/{id}/wait")
  public GoalWaitRepository.Wait waiting(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> waits().show(projectId, id));
  }

  /** Web APIによる操作で用いるactivate待機を返す。 */
  @PostMapping("/{id}/wait/activate")
  public GoalWaitRepository.Wait activateWait(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitVersion request) {
    return control(() -> waits().activate(projectId, id, version(request)));
  }

  /** 中断した作業を引き継ぐため、待機を再開する。 */
  @PostMapping("/{id}/wait/resume")
  public GoalWaitRepository.Wait resumeWait(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitVersion request) {
    return control(() -> waits().resume(projectId, id, version(request)));
  }

  /** 利用者が不要とした実行を止めるため、待機をキャンセルする。 */
  @PostMapping("/{id}/wait/cancel")
  public GoalWaitRepository.Wait cancelWait(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitVersion request) {
    return control(() -> waits().cancel(projectId, id, version(request)));
  }

  /** Web APIによる操作で用いる形式のバージョンを返す。 */
  private long version(WaitVersion request) {
    if (request == null) throw new IllegalArgumentException("Wait revision required");
    return request.expectedVersion();
  }

  /** Web APIによる操作で用いる制御を返す。 */
  private static <T> T control(Supplier<T> action) {
    try {
      return action.get();
    } catch (IllegalStateException conflict) {
      throw new OperationConflictException();
    }
  }
}
