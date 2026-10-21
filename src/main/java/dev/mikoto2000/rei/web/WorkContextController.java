package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.workcontext.WorkContext;
import dev.mikoto2000.rei.workcontext.WorkContextFormatter;
import dev.mikoto2000.rei.workcontext.WorkContextGit;
import dev.mikoto2000.rei.workcontext.WorkContextProperties;
import dev.mikoto2000.rei.workcontext.WorkContextService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作業文脈をWeb APIの操作として受け付ける。
 *
 * <p>Same authenticated /api/v1 boundary as projects and sessions; no mutable UI project state.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkContextController {
  /** サービス。 */
  private final WorkContextService service;

  /** Gitの情報。 */
  private final WorkContextGit git;

  /** この機能の動作設定。 */
  private final WorkContextProperties properties;

  /** 指定された値や利用先を使って、WorkContextControllerを初期化する。 */
  public WorkContextController(
      WorkContextService service, WorkContextGit git, WorkContextProperties properties) {
    this.service = service;
    this.git = git;
    this.properties = properties;
  }

  /** Web APIによる操作で用いる現在を返す。 */
  @GetMapping("/api/v1/projects/{projectId}/work-context")
  public WorkContext current(@PathVariable String projectId) {
    return service
        .current(projectId)
        .orElseThrow(() -> new ResourceNotFoundException("Work Context"));
  }

  /**
   * Web APIによる操作で受け渡す要約を表す。
   *
   * @param autoPresent 自動存在する
   * @param hasContext 保有文脈
   * @param text テキスト
   */
  public record Summary(boolean autoPresent, boolean hasContext, String text) {}

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /** Web APIによる操作で用いるレポートを返す。 */
  @GetMapping("/api/v1/projects/{projectId}/work-context/report")
  public Text report(@PathVariable String projectId) {
    var project = service.project(projectId);
    return new Text(
        new WorkContextFormatter()
            .details(
                service.current(projectId).orElse(null),
                git.capture(project.root(), Instant.now())));
  }

  /** Web APIによる操作で用いるレポート履歴を返す。 */
  @GetMapping("/api/v1/projects/{projectId}/work-context/history/report")
  public Text reportHistory(@PathVariable String projectId) {
    var rows = service.history(projectId, 100);
    return new Text(
        rows.isEmpty()
            ? "Work Context: 履歴はまだありません。"
            : rows.stream()
                .map(
                    context ->
                        "revision="
                            + context.revision()
                            + " updated="
                            + context.updatedAt()
                            + " items="
                            + context.items().size())
                .collect(java.util.stream.Collectors.joining("\n")));
  }

  /** Web APIによる操作で用いる要約を返す。 */
  @GetMapping("/api/v1/projects/{projectId}/work-context/summary")
  public Summary summary(@PathVariable String projectId) {
    var project = service.project(projectId);
    var saved = service.current(projectId).orElse(null);
    return new Summary(
        properties.autoPresent(),
        saved != null,
        new WorkContextFormatter()
            .summary(saved, saved == null ? null : git.capture(project.root(), Instant.now())));
  }

  /**
   * Web APIによる操作で受け渡す改訂番号を表す。
   *
   * @param revision 改訂番号
   * @param updatedAt 更新日時
   * @param itemCount 項目件数
   */
  public record Revision(long revision, Instant updatedAt, int itemCount) {}

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  @GetMapping("/api/v1/projects/{projectId}/work-context/history")
  public List<Revision> history(
      @PathVariable String projectId, @RequestParam(defaultValue = "20") int limit) {
    return service.history(projectId, limit).stream()
        .map(c -> new Revision(c.revision(), c.updatedAt(), c.items().size()))
        .toList();
  }

  /** Web APIによる操作で用いる改訂番号を返す。 */
  @GetMapping("/api/v1/projects/{projectId}/work-context/history/{revision}")
  public WorkContext revision(@PathVariable String projectId, @PathVariable long revision) {
    return service.revision(projectId, revision);
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  @PostMapping("/api/v1/sessions/{sessionId}/work-context/update")
  public Map<String, Object> update(@PathVariable String sessionId) {
    return service
        .update(sessionId, null)
        .<Map<String, Object>>map(
            c ->
                Map.of(
                    "projectId",
                    c.projectId(),
                    "revision",
                    c.revision(),
                    "message",
                    "Saved finalized turns"))
        .orElseGet(() -> Map.of("message", "No finalized turns"));
  }
}
