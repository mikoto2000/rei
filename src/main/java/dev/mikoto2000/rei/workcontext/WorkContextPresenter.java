package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 作業文脈の引き継ぎに必要な作業文脈Presenterを扱う。
 *
 * <p>Seen state belongs to each UI client, not a global project or the persisted data.
 */
@Component
public class WorkContextPresenter {
  /** サービス。 */
  private final WorkContextService service;

  /** この機能の動作設定。 */
  private final WorkContextProperties properties;

  /** Gitの情報。 */
  private final WorkContextGit git;

  /** 指定された値や利用先を使って、WorkContextPresenterを初期化する。 */
  public WorkContextPresenter(
      WorkContextService service, WorkContextProperties properties, WorkContextGit git) {
    this.service = service;
    this.properties = properties;
    this.git = git;
  }

  /** 作業文脈の引き継ぎで用いる存在するを返す。 */
  public Optional<String> present(ProjectContext project, String session, Set<String> seen) {
    return present(project, session, seen, true);
  }

  /**
   * 作業文脈の引き継ぎで用いる存在する保存済みを返す。
   *
   * <p>Automatic Shell presentation uses saved background only, without a Git probe.
   */
  public Optional<String> presentSaved(ProjectContext project, String session, Set<String> seen) {
    return present(project, session, seen, false);
  }

  /** 作業文脈の引き継ぎで用いる存在するを返す。 */
  private Optional<String> present(
      ProjectContext project, String session, Set<String> seen, boolean inspectGit) {
    if (!properties.autoPresent()) return Optional.empty();
    String key = project.id() + ":" + Objects.toString(session, "unselected");
    synchronized (seen) {
      if (seen.contains(key)) return Optional.empty();
      var saved = service.current(project.id()).orElse(null);
      var current =
          saved == null || !inspectGit ? null : git.capture(project.root(), Instant.now());
      String text = new WorkContextFormatter().summary(saved, current);
      seen.add(key);
      return Optional.of(text);
    }
  }
}
