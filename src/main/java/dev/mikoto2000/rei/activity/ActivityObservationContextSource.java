package dev.mikoto2000.rei.activity;

import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.workcontext.WorkContext;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 作業状況の把握に必要な作業状況観測内容文脈取得元を扱う。
 *
 * <p>Read-only opt-in capture. Never copies work text, commands, results or absolute repository
 * paths.
 */
public final class ActivityObservationContextSource implements ActivityEvidenceSource {
  /** 作業状況の把握に必要なGit取得記録を扱う。 */
  @FunctionalInterface
  public interface GitCapture {
    /** 後から状況を確認できるよう、Git取得記録を記録する。 */
    WorkContext.GitState capture(Path root, Instant at);
  }

  /** この機能の動作設定。 */
  private final ActivityProperties properties;

  /** プロジェクト一覧。 */
  private final Supplier<ProjectContext> projects;

  /** 文脈一覧。 */
  private final Function<String, Optional<WorkContext>> contexts;

  /** Gitの情報。 */
  private final GitCapture git;

  /** 指定された値や利用先を使って、ActivityObservationContextSourceを初期化する。 */
  public ActivityObservationContextSource(
      ActivityProperties properties,
      Supplier<ProjectContext> projects,
      Function<String, Optional<WorkContext>> contexts,
      GitCapture git) {
    this.properties = properties;
    this.projects = projects;
    this.contexts = contexts;
    this.git = git;
  }

  /** 判断に必要な情報を集め、後続の処理へ渡す。 */
  @Override
  public Contribution collect(Instant at) {
    var project = projects.get();
    if (project == null) return new Contribution("", "", List.of());
    if (!properties.isWorkContextEnabled()) return basic(project);
    try {
      return capture(project, at);
    } catch (java.util.concurrent.CancellationException error) {
      throw error;
    } catch (RuntimeException error) {
      if (Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
      // Context/Git failure must not remove the otherwise available selected Project evidence.
      var current = projects.get();
      return basic(current);
    }
  }

  /** 後から状況を確認できるよう、作業状況観測内容文脈取得元を記録する。 */
  private Contribution capture(ProjectContext project, Instant at) {
    var context = contexts.apply(project.id()).orElse(null);
    if (context != null
        && (!project.id().equals(context.projectId()) || context.updatedAt().isAfter(at)))
      return basic(project);
    // Reuse bounded read-only Git infrastructure; failures yield unknown branch/commit, never old
    // metadata.
    var captured = git.capture(project.root(), at);
    var current = projects.get();
    if (current == null
        || !project.id().equals(current.id())
        || !project.root().equals(current.root())) return basic(current);
    var metadata =
        captured == null || !at.equals(captured.capturedAt())
            ? null
            : new ActivityEvidence.GitReference(
                captured.branch(), captured.commit(), captured.capturedAt());
    var references = new ArrayList<ActivityEvidence.ItemReference>();
    boolean partial = false;
    if (context != null) {
      var items =
          context.items().stream()
              .filter(
                  i ->
                      i.status() != WorkContext.Status.SUPERSEDED
                          && i.status() != WorkContext.Status.WITHDRAWN)
              .sorted(
                  Comparator.comparing(WorkContext.Item::updatedAt)
                      .reversed()
                      .thenComparing(WorkContext.Item::id))
              .toList();
      partial = items.size() > 20;
      for (var item : items.stream().limit(20).toList()) {
        var sources =
            item.evidence().stream()
                .filter(
                    e ->
                        e.origin() == WorkContext.Origin.TOOL
                            && e.observedAt() != null
                            && !e.observedAt().isAfter(at))
                .sorted(
                    Comparator.comparing(WorkContext.Evidence::observedAt)
                        .reversed()
                        .thenComparing(WorkContext.Evidence::id))
                .toList();
        partial |= sources.size() > 8;
        references.add(
            new ActivityEvidence.ItemReference(
                item.id(),
                item.kind().name(),
                item.status().name(),
                item.certainty().name(),
                sources.stream()
                    .limit(8)
                    .map(
                        e ->
                            new ActivityEvidence.SourceReference(
                                e.id(),
                                e.sessionId(),
                                e.turnId(),
                                e.runId(),
                                e.toolCallId(),
                                relativeFile(project.root(), e.filePath()),
                                e.observedAt()))
                    .toList()));
      }
    }
    var saved =
        new ActivityEvidence.WorkReference(
            project.id(),
            at,
            context == null ? 0 : context.revision(),
            context == null ? null : context.updatedAt(),
            metadata,
            references,
            partial);
    return new Contribution(project.name(), project.id(), List.of(), saved);
  }

  /** 作業状況の把握で用いるbasicを返す。 */
  private static Contribution basic(ProjectContext project) {
    return new Contribution(
        project == null ? "" : project.name(), project == null ? "" : project.id(), List.of());
  }

  /** 作業状況の把握で用いる相対ファイルを返す。 */
  private static String relativeFile(Path root, String raw) {
    if (raw == null
        || raw.isBlank()
        || raw.length() > 1024
        || raw.chars().anyMatch(Character::isISOControl)) return null;
    try {
      var file = Path.of(raw);
      var absolute = (file.isAbsolute() ? file : root.resolve(file)).normalize();
      if (!absolute.startsWith(root)) return null;
      var relative = root.relativize(absolute).toString().replace('\\', '/');
      for (var segment : relative.split("/")) {
        var name = segment.toLowerCase(Locale.ROOT);
        if (Set.of(".git", ".aws", ".ssh", ".codex", ".agents").contains(name)
            || name.startsWith(".env")
            || name.contains("secret")
            || name.contains("credential")
            || name.endsWith(".pem")
            || name.endsWith(".key")) return null;
      }
      return relative.isBlank() ? null : relative;
    } catch (java.nio.file.InvalidPathException error) {
      return null;
    }
  }
}
