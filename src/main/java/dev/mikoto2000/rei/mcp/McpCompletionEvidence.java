package dev.mikoto2000.rei.mcp;

import dev.mikoto2000.rei.artifact.ArtifactStore;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.reflection.GoalReflectionRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MCPによる機能の提供に必要なMCP入力補完根拠を扱う。
 *
 * <p>Project only saved Goal verification facts; never reverify files or trust assistant claims.
 */
public final class McpCompletionEvidence {
  /** 目標一覧。 */
  private final GoalRepository goals;

  /** 振り返り一覧。 */
  private final GoalReflectionRepository reflections;

  /** 成果物一覧。 */
  private final ArtifactStore artifacts;

  /** 指定された値や利用先を使って、McpCompletionEvidenceを初期化する。 */
  public McpCompletionEvidence(
      GoalRepository goals, GoalReflectionRepository reflections, ArtifactStore artifacts) {
    this.goals = goals;
    this.reflections = reflections;
    this.artifacts = artifacts;
  }

  /** MCPによる機能の提供で用いる入力補完を返す。 */
  public Map<String, Object> completion(McpRequestStore.Request request, boolean succeeded) {
    var verified = new HashSet<String>();
    boolean failed = false;
    if (goals != null && reflections != null)
      for (var goal : goals.list(request.project())) {
        if (!Objects.equals(goal.currentRunId(), request.run())
            || !goal.sessionId().equals(request.session())
            || goal.completion() == null) continue;
        var saved =
            reflections.list(request.project()).stream()
                .filter(
                    r ->
                        r.goalId().equals(goal.id())
                            && r.runId().equals(request.run())
                            && r.sessionId().equals(request.session())
                            && reflections.matchesCriteria(r, goal))
                .toList();
        if (goal.status().equals("COMPLETED")
            && saved.stream()
                .anyMatch(r -> r.actual().equals("VERIFIED") && r.goalStatus().equals("COMPLETED")))
          goal.completion()
              .requirements()
              .forEach(
                  r -> {
                    if (r.required() && request.criteria().contains(r.statement()))
                      verified.add(r.statement());
                  });
        failed |= saved.stream().anyMatch(r -> r.actual().equals("REJECTED"));
      }
    return project(succeeded, request.criteria(), verified, failed);
  }

  /** MCPによる機能の提供で用いるプロジェクトを返す。 */
  static Map<String, Object> project(
      boolean succeeded, List<String> criteria, Set<String> verified, boolean failed) {
    long count = criteria.stream().filter(verified::contains).count();
    String state =
        failed
            ? "FAILED"
            : criteria.isEmpty()
                ? "UNKNOWN"
                : count == criteria.size()
                    ? "VERIFIED"
                    : count > 0 ? "PARTIALLY_VERIFIED" : "UNVERIFIED";
    return Map.of(
        "execution_succeeded",
        succeeded,
        "acceptance_status",
        state,
        "verified_criteria",
        count,
        "total_criteria",
        criteria.size());
  }

  /** MCPによる機能の提供で用いる成果物一覧を返す。 */
  public List<Map<String, Object>> artifacts(McpRequestStore.Request request) {
    if (artifacts == null) return List.of();
    return artifacts
        .list(request.project(), request.session(), request.run(), 100, null)
        .items()
        .stream()
        .filter(a -> request.run().equals(a.runId()) && request.session().equals(a.sessionId()))
        .map(
            a ->
                Map.<String, Object>of(
                    "artifact_id",
                    a.artifactId(),
                    "filename",
                    CredentialRedactor.redact(a.filename()),
                    "media_type",
                    a.mediaType(),
                    "size",
                    a.size(),
                    "sha256",
                    a.sha256(),
                    "reference",
                    a.storageReference()))
        .toList();
  }
}
