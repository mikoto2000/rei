package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 対象を限定した記憶をWeb APIの操作として受け付ける。
 *
 * <p>Named memory operations with explicit Project ownership; reuses legacy Shell rendering.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/memory")
public final class ScopedMemoryController {
  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 記憶。 */
  private final MemoryCommandSupport memory;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /** 指定された値や利用先を使って、ScopedMemoryControllerを初期化する。 */
  public ScopedMemoryController(ProjectRegistry projects, MemoryCommandSupport memory) {
    this.projects = projects;
    this.memory = memory;
  }

  /** Web APIによる操作で用いるプロジェクトを返す。 */
  private String project(String id) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (id == null || id.isBlank() || id.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    return projects
        .resolveById(id)
        .orElseThrow(
            () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"))
        .id();
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public Text list(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return new Text(memory.listForProject(project(projectId), limit, offset));
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  @GetMapping("/search")
  public Text search(
      @PathVariable String projectId,
      @RequestParam String query,
      @RequestParam(defaultValue = "10") int limit) {
    if (query == null || query.length() > 16384)
      throw new IllegalArgumentException("Invalid memory query");
    return new Text(memory.searchForProject(project(projectId), query, limit));
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/{id}")
  public Text show(@PathVariable String projectId, @PathVariable String id) {
    return new Text(memory.showForProject(project(projectId), id));
  }

  /** Web APIによる操作で用いる記憶の削除を返す。 */
  @PostMapping("/{id}/forget")
  public Text forget(@PathVariable String projectId, @PathVariable String id) {
    return new Text(memory.forgetForProject(project(projectId), id));
  }
}
