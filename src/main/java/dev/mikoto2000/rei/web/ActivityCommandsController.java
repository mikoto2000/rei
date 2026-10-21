package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.activity.ActivityCommand;
import dev.mikoto2000.rei.activity.PeriodCoaching;
import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.project.ProjectClientScope;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作業状況コマンド一覧をWeb APIの操作として受け付ける。
 *
 * <p>Explicit journal controls; no action/method selector is supplied to the Backend.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/activity/commands")
public final class ActivityCommandsController {
  /** 作業状況。 */
  private final ActivityCommand activity;

  /** 登録台帳。 */
  private final ProjectRegistry registry;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 実行一覧。 */
  private final IntegrationRunSubmitService runs;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す有効化を表す。
   *
   * @param enabled 有効状態
   * @param expectedRevision 期待する内容改訂番号
   */
  public record Enable(Boolean enabled, Long expectedRevision) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す設定を表す。
   *
   * @param settings 設定
   * @param expectedRevision 期待する内容改訂番号
   */
  public record Configure(PeriodCoaching.Settings settings, Long expectedRevision)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す日付を表す。
   *
   * @param date 日付
   */
  public record Date(String date) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す対象範囲を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   */
  public record Scope(String projectId, String sessionId) implements StrictApiRequest {}

  /** 指定された値や利用先を使って、ActivityCommandsControllerを初期化する。 */
  public ActivityCommandsController(
      ActivityCommand activity,
      ProjectRegistry registry,
      ProjectService projects,
      IntegrationRunSubmitService runs) {
    this.activity = activity;
    this.registry = registry;
    this.projects = projects;
    this.runs = runs;
  }

  /** Web APIによる操作で用いる時系列の記録を返す。 */
  @GetMapping("/timeline")
  public Text timeline(
      @RequestParam(defaultValue = "today") String date,
      @RequestParam(defaultValue = "false") boolean verbose) {
    date(date);
    return text(activity.reportTimeline(date, verbose));
  }

  /** Web APIによる操作で用いる一時停止を返す。 */
  @PostMapping("/pause")
  public Text pause() {
    return text(activity.reportPause());
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  @PostMapping("/resume")
  public Text resume() {
    return text(activity.reportResume());
  }

  /** Web APIによる操作で用いる要約を返す。 */
  @GetMapping("/summary")
  public Text summary(@RequestParam(defaultValue = "today") String date) {
    date(date);
    return text(activity.reportSummary(date));
  }

  /** Web APIによる操作で用いる週次を返す。 */
  @GetMapping("/weekly")
  public Text weekly(@RequestParam(defaultValue = "today") String date) {
    date(date);
    return text(activity.reportWeekly(date));
  }

  /** Web APIによる操作で用いる月次を返す。 */
  @GetMapping("/monthly")
  public Text monthly(@RequestParam(defaultValue = "today") String date) {
    date(date);
    return text(activity.reportMonthly(date));
  }

  /** Web APIによる操作で用いる文脈を返す。 */
  @GetMapping("/context")
  public Text context(
      @RequestParam String projectId, @RequestParam(defaultValue = "today") String date) {
    date(date);
    var project =
        registry
            .resolveById(projectId)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    try (var scope = ProjectClientScope.open(projects.newClient())) {
      projects.cd(project.root().toString());
      return text(activity.reportContext(date));
    }
  }

  /** Web APIによる操作で用いる助言状態を返す。 */
  @GetMapping("/coaching/status")
  public Text coachingStatus() {
    return text(activity.reportCoachingStatus());
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  @PostMapping("/coaching/enabled")
  public Text enabled(@RequestBody Enable request) {
    if (request == null || request.enabled() == null)
      throw new IllegalArgumentException("Enabled flag required");
    return text(
        activity.reportCoachingEnabled(request.enabled(), revision(request.expectedRevision())));
  }

  /** Web APIによる操作で用いる設定を返す。 */
  @PostMapping("/coaching/settings")
  public Text configure(@RequestBody Configure request) {
    if (request == null || request.settings() == null || request.settings().enabled())
      throw new IllegalArgumentException("Save disabled criteria before explicit enable");
    return text(
        activity.reportCoachingConfigure(request.settings(), revision(request.expectedRevision())));
  }

  /** Web APIによる操作で用いる助言週次を返す。 */
  @PostMapping("/coaching/weekly")
  public Text coachingWeekly(@RequestBody Date request) {
    if (request == null) throw new IllegalArgumentException("Date request required");
    date(request.date());
    return text(activity.reportCoachingWeekly(request.date()));
  }

  /** Web APIによる操作で用いる助言月次を返す。 */
  @PostMapping("/coaching/monthly")
  public Text coachingMonthly(@RequestBody Date request) {
    if (request == null) throw new IllegalArgumentException("Date request required");
    date(request.date());
    return text(activity.reportCoachingMonthly(request.date()));
  }

  /** Web APIによる操作で用いる作業習慣状態を返す。 */
  @GetMapping("/behavior/status")
  public Text behaviorStatus() {
    return text(activity.reportBehaviorStatus());
  }

  /** Web APIによる操作で用いる作業習慣有効状態を返す。 */
  @PostMapping("/behavior/enabled")
  public Text behaviorEnabled(@RequestBody Enable request) {
    if (request == null || request.enabled() == null)
      throw new IllegalArgumentException("Enabled flag required");
    return text(activity.reportBehaviorEnabled(request.enabled()));
  }

  /** Web APIによる操作で用いる作業習慣評価を返す。 */
  @GetMapping("/behavior/evaluate")
  public Text behaviorEvaluate() {
    return text(activity.reportBehaviorEvaluation());
  }

  /** Web APIによる操作で用いる不確実なを返す。 */
  @GetMapping("/behavior/uncertain")
  public Text uncertain(@RequestParam(defaultValue = "20") int top) {
    return text(activity.reportUncertain(top));
  }

  /** Web APIによる操作で用いる提案作業習慣を返す。 */
  @PostMapping("/behavior/suggest-rules")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> suggestBehavior(
      @RequestBody Scope request) {
    scope(request);
    return accepted(runs.suggestBehaviorRules(request.projectId(), request.sessionId()));
  }

  /** Web APIによる操作で用いる分類状態を返す。 */
  @GetMapping("/classification/status")
  public Text classificationStatus() {
    return text(activity.reportClassificationStatus());
  }

  /** 変更された設定や記録を反映するため、作業状況コマンド一覧受付窓口を読み直す。 */
  @PostMapping("/classification/reload")
  public Text reload() {
    return text(activity.reportClassificationReload());
  }

  /** Web APIによる操作で用いるunknownsを返す。 */
  @GetMapping("/classification/unknowns")
  public Text unknowns(@RequestParam(defaultValue = "20") int top) {
    return text(activity.reportUnknown(top));
  }

  /** Web APIによる操作で用いる規則一覧を返す。 */
  @GetMapping("/classification/rules")
  public Text rules() {
    return text(activity.reportRules());
  }

  /** Web APIによる操作で用いる提案分類を返す。 */
  @PostMapping("/classification/suggest-rules")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> suggestClassification(
      @RequestBody Scope request) {
    scope(request);
    return accepted(runs.suggestClassificationRules(request.projectId(), request.sessionId()));
  }

  /** Web APIによる操作に関する対象範囲を処理する。 */
  private static void scope(Scope request) {
    if (request == null || request.projectId() == null || request.projectId().isBlank())
      throw new IllegalArgumentException("Project required");
  }

  /** Web APIによる操作に関する日付を処理する。 */
  private static void date(String value) {
    if (value != null
        && (value.isBlank()
            || value.length() > 128
            || value.chars().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Invalid date");
  }

  /** Web APIによる操作で用いる改訂番号を返す。 */
  private static long revision(Long value) {
    if (value == null || value < 0 || value == Long.MAX_VALUE)
      throw new IllegalArgumentException("Viewed coaching revision required");
    return value;
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
