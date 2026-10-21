package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.MemoryRunSubmitService;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 待機をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/memory")
public final class SleepController {
  /** 実行一覧。 */
  private final MemoryRunSubmitService runs;

  /** 補助。 */
  private final MemoryCommandSupport support;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 会話一覧。 */
  private final SessionLifecycle sessions;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す待機要求を表す。
   *
   * @param sessionId 会話の識別子
   * @param preview 事前確認
   */
  public record SleepRequest(String sessionId, boolean preview) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すキャンセルを表す。
   *
   * @param revision 改訂番号
   */
  public record Cancellation(Long revision) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す保守を表す。
   *
   * @param sessionId 会話の識別子
   * @param approve 承認
   */
  public record Maintenance(String sessionId, boolean approve) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す書き出しを表す。
   *
   * @param directory ディレクトリ
   */
  public record Export(String directory) implements StrictApiRequest {}

  /** 保存情報を書き出すコマンドの窓口。 */
  private dev.mikoto2000.rei.memory.command.MemoryCommand.ExportCommand exporter;

  /** Web APIによる操作で使用するExporterを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setExporter(dev.mikoto2000.rei.memory.command.MemoryCommand.ExportCommand exporter) {
    this.exporter = exporter;
  }

  /** 指定された値や利用先を使って、SleepControllerを初期化する。 */
  public SleepController(
      MemoryRunSubmitService runs,
      MemoryCommandSupport support,
      ProjectRegistry projects,
      SessionLifecycle sessions) {
    this.runs = runs;
    this.support = support;
    this.projects = projects;
    this.sessions = sessions;
  }

  /** Web APIによる操作で用いる待機を返す。 */
  @PostMapping("/sleep")
  public ResponseEntity<ChatResponse> sleep(
      @PathVariable String projectId,
      @RequestBody SleepRequest request,
      @RequestHeader("Idempotency-Key") String key) {
    if (request == null) throw new IllegalArgumentException("Sleep request required");
    var run = runs.sleep(projectId, request.sessionId(), request.preview(), key);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  @PostMapping("/summarize")
  public ResponseEntity<ChatResponse> summarize(
      @PathVariable String projectId,
      @RequestBody Maintenance request,
      @RequestHeader("Idempotency-Key") String key) {
    if (request == null) throw new IllegalArgumentException("Memory request required");
    return accepted(runs.summarize(projectId, request.sessionId(), request.approve(), key));
  }

  /** Web APIによる操作で用いるconsolidateを返す。 */
  @PostMapping("/consolidate")
  public ResponseEntity<ChatResponse> consolidate(
      @PathVariable String projectId,
      @RequestBody Maintenance request,
      @RequestHeader("Idempotency-Key") String key) {
    if (request == null) throw new IllegalArgumentException("Memory request required");
    return accepted(runs.consolidate(projectId, request.sessionId(), request.approve(), key));
  }

  /** Web APIによる操作で用いる書き出しを返す。 */
  @PostMapping("/export")
  public Text export(@PathVariable String projectId, @RequestBody Export request) {
    project(projectId);
    if (request == null) throw new IllegalArgumentException("Export request required");
    String directory = request.directory();
    if (directory != null
        && (directory.isBlank()
            || directory.length() > 4096
            || directory.chars().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Invalid export directory");
    if (exporter == null) throw new IllegalStateException("Memory export is unavailable");
    return new Text(exporter.render(directory));
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private ResponseEntity<ChatResponse> accepted(dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/runs/" + run.runId()))
        .body(new ChatResponse(run.runId(), run.conversationId(), run.runId()));
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  @GetMapping("/sleep/status")
  public Text status(
      @PathVariable String projectId, @RequestParam(required = false) String sessionId) {
    project(projectId);
    if (sessionId != null) sessions.validate(sessionId, projectId);
    return new Text(support.statusForProject(projectId, sessionId));
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  @GetMapping("/sleep/history")
  public Text history(@PathVariable String projectId) {
    project(projectId);
    return new Text(support.historyForProject(projectId));
  }

  /** Web APIによる操作で用いる要求一覧を返す。 */
  @GetMapping("/sleep/requests")
  public Text requests(@PathVariable String projectId) {
    project(projectId);
    return new Text(support.autoSleepRequestsForProject(projectId));
  }

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/sleep/requests/{sessionId}/cancel")
  public Text cancel(
      @PathVariable String projectId,
      @PathVariable String sessionId,
      @RequestBody Cancellation request) {
    project(projectId);
    sessions.validate(sessionId, projectId);
    if (request == null || request.revision() == null)
      throw new IllegalArgumentException("Observed revision required");
    return new Text(
        support.cancelAutoSleepRequestForProject(projectId, sessionId, request.revision()));
  }

  /** Web APIによる操作に関するプロジェクトを処理する。 */
  private void project(String id) {
    projects
        .resolveById(id)
        .orElseThrow(
            () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
  }
}
