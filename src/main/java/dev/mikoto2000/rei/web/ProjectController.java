package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.project.ProjectQueryService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** プロジェクトをWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProjectController {
  /** プロジェクト一覧。 */
  private final ProjectQueryService projects;

  /** 登録台帳。 */
  private dev.mikoto2000.rei.core.project.ProjectRegistry registry;

  /** Web APIによる操作で使用する登録台帳を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRegistry(dev.mikoto2000.rei.core.project.ProjectRegistry registry) {
    this.registry = registry;
  }

  /** 指定された値や利用先を使って、ProjectControllerを初期化する。 */
  public ProjectController(ProjectQueryService projects) {
    this.projects = projects;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping("/api/v1/projects")
  public List<ProjectResponse> list() {
    return projects.list().stream()
        .map(project -> new ProjectResponse(project.id(), project.name(), project.path()))
        .toList();
  }

  /**
   * Web APIによる操作で受け渡す登録要求を表す。
   *
   * @param path パス
   */
  public record RegisterRequest(String path) implements StrictApiRequest {}

  /** 指定された対象を管理から取り除く。 */
  @DeleteMapping("/api/v1/projects/{id}")
  public ProjectResponse remove(@PathVariable String id) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (id == null || id.isBlank() || id.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    if (registry == null) throw new IllegalStateException("Project registry unavailable");
    var project =
        registry
            .resolveById(id)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    registry.remove(project.root());
    return new ProjectResponse(project.id(), project.name(), project.root().toString());
  }

  /** 後から対象を参照できるよう、プロジェクト受付窓口を登録する。 */
  @PostMapping("/api/v1/projects/register")
  public ProjectResponse register(@RequestBody RegisterRequest request) {
    if (request == null
        || request.path() == null
        || request.path().isBlank()
        || request.path().length() > 4096)
      throw new IllegalArgumentException("Invalid Project path");
    if (registry == null) throw new IllegalStateException("Project registry unavailable");
    var path = java.nio.file.Path.of(request.path());
    if (!path.isAbsolute()) throw new IllegalArgumentException("Absolute Project path required");
    var project = registry.resolve(path);
    return new ProjectResponse(project.id(), project.name(), project.root().toString());
  }
}
