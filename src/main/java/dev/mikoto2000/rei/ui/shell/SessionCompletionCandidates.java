package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.completion.CompletionCandidate;
import dev.mikoto2000.rei.core.completion.CompletionContext;
import dev.mikoto2000.rei.core.completion.CompletionMetadata;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 会話入力補完で利用できる入力候補を提供する。
 *
 * <p>Session IDs are inserted; titles are descriptions, never lookup keys. No history files are
 * read.
 */
@Component
public class SessionCompletionCandidates implements CompletionMetadata {
  /** 会話一覧。 */
  private final SessionRepository sessions;

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SessionCompletionCandidates() {
    this(null);
  }

  /** 指定された値や利用先を使って、SessionCompletionCandidatesを初期化する。 */
  @Autowired
  public SessionCompletionCandidates(SessionRepository sessions) {
    this.sessions = sessions;
  }

  /** ターミナルでの対話で用いる種別一覧を返す。 */
  @Override
  public Set<String> types(CompletionContext context) {
    return Set.of("choices");
  }

  /** ターミナルでの対話で判断に用いるacceptsの条件を確認する。 */
  protected boolean accepts(String projectId) {
    return true;
  }

  /** ターミナルでの対話で用いる選択肢を返す。 */
  @Override
  public List<CompletionCandidate> choices(CompletionContext context) {
    if (sessions == null) return List.of();
    return sessions.completionSnapshot().stream()
        .filter(row -> accepts(row.projectId()))
        .sorted(
            Comparator.comparing(dev.mikoto2000.rei.application.session.SessionMetadata::updatedAt)
                .reversed())
        .map(
            row ->
                new CompletionCandidate(
                    row.sessionId(), row.sessionId(), row.title(), "session", true))
        .toList();
  }

  /** ターミナルでの対話に必要な現在プロジェクトを扱う。 */
  @Component
  public static class CurrentProject extends SessionCompletionCandidates {
    /** プロジェクト一覧。 */
    private final ProjectService projects;

    /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public CurrentProject() {
      this(null, null);
    }

    /** 指定された値や利用先を使って、CurrentProjectを初期化する。 */
    @Autowired
    public CurrentProject(SessionRepository sessions, ProjectService projects) {
      super(sessions);
      this.projects = projects;
    }

    /** ターミナルでの対話で判断に用いるacceptsの条件を確認する。 */
    @Override
    protected boolean accepts(String projectId) {
      return projects != null
          && projects.completionProjects().stream()
              .anyMatch(
                  project ->
                      project.id().equals(projectId)
                          && project.root().equals(projects.currentProject()));
    }
  }
}
