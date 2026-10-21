package dev.mikoto2000.rei.application.project;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.util.List;

/**
 * プロジェクト検索条件のユースケースを実現する。
 *
 * <p>Read registered identities without changing the Shell's current project.
 */
public final class ProjectQueryService {
  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 指定された値や利用先を使って、ProjectQueryServiceを初期化する。 */
  public ProjectQueryService(ProjectRegistry projects) {
    this.projects = projects;
  }

  /**
   * プロジェクトの照会で受け渡すプロジェクト要約を表す。
   *
   * @param id 識別子
   * @param name 名前
   * @param path パス
   */
  public record ProjectSummary(String id, String name, String path) {}

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<ProjectSummary> list() {
    return projects.list().stream()
        .map(project -> new ProjectSummary(project.id(), project.name(), project.root().toString()))
        .toList();
  }
}
