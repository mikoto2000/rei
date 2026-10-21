package dev.mikoto2000.rei.externalagent;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 外部レビューを保存・検索するための窓口。
 *
 * <p>Durable review outcomes and opt-in provider UUIDs, never prompts, source contents or process
 * logs.
 */
@Repository
public class ExternalReviewRepository {
  /**
   * 外部エージェントへの委譲で受け渡すレビューを表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param projectRoot 作業対象のルートディレクトリ
   * @param sessionId 会話の識別子
   * @param runId 実行の識別子
   * @param target 対象
   * @param previousId 前回識別子
   * @param status 状態
   * @param createdAt 作成日時
   * @param completedAt 完了日時
   * @param result 結果
   * @param agent エージェント
   */
  public record Review(
      String id,
      String projectId,
      String projectRoot,
      String sessionId,
      String runId,
      String target,
      String previousId,
      String status,
      Instant createdAt,
      Instant completedAt,
      ExternalAgentResult result,
      String agent) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final com.fasterxml.jackson.databind.ObjectMapper mapper =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** 指定された値や利用先を使って、ExternalReviewRepositoryを初期化する。 */
  public ExternalReviewRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    db = JdbcClient.create(source);
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS external_reviews(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,target TEXT,previous TEXT,status TEXT NOT NULL,created INTEGER NOT NULL,completed INTEGER,result TEXT)")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS external_reviews_project ON external_reviews(project,created)")
        .update();
    if (!db.sql("PRAGMA table_info(external_reviews)")
        .query((rs, index) -> rs.getString("name"))
        .list()
        .contains("agent"))
      db.sql("ALTER TABLE external_reviews ADD COLUMN agent TEXT NOT NULL DEFAULT 'codex'")
          .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS external_review_continuations(previous TEXT PRIMARY KEY,review TEXT NOT NULL)")
        .update();
  }

  /**
   * 要求された処理を利用できるよう、継続入力を開始する。
   *
   * <p>An attempted continuation consumes its parent even after failure or a crash. Never replay
   * it.
   */
  public void startContinuation(
      AgentRunContext owner, String id, Path root, String target, String previous) {
    startContinuation(owner, id, root, target, previous, "codex");
  }

  /** 要求された処理を利用できるよう、継続入力を開始する。 */
  public void startContinuation(
      AgentRunContext owner, String id, Path root, String target, String previous, String agent) {
    var parent = get(owner.projectId(), previous);
    if (!Set.of("codex", "claude").contains(agent)
        || !parent.agent().equals(agent)
        || !parent.projectRoot().equals(root.toString())
        || parent.result() == null
        || !parent.result().success()
        || !ExternalAgentResult.validSessionId(parent.result().externalSessionId()))
      throw new IllegalArgumentException("Resumable completed review required");
    if (db.sql("INSERT OR IGNORE INTO external_review_continuations(previous,review) VALUES(?,?)")
            .params(previous, id)
            .update()
        != 1)
      throw new IllegalArgumentException(
          "This review's continuation was already attempted; use its completed successor or request a fresh re-review");
    start(owner, id, root, target, previous, agent);
  }

  /** 外部エージェントへの委譲において、継続入力Attemptedに該当するか判定する。 */
  public boolean continuationAttempted(String previous) {
    return db.sql("SELECT count(*) FROM external_review_continuations WHERE previous=?")
            .param(previous)
            .query(Integer.class)
            .single()
        > 0;
  }

  /** 要求された処理を開始する。 */
  public void start(AgentRunContext owner, String id, Path root, String target, String previous) {
    start(owner, id, root, target, previous, "codex");
  }

  /** 要求された処理を開始する。 */
  public void start(
      AgentRunContext owner, String id, Path root, String target, String previous, String agent) {
    if (!Set.of("codex", "claude").contains(agent))
      throw new IllegalArgumentException("Unsupported review provider");
    if (owner.projectId() == null || owner.projectId().isBlank())
      throw new IllegalArgumentException("Project required");
    if (previous != null) {
      var parent = get(owner.projectId(), previous);
      if (!parent.agent().equals(agent)
          || !parent.projectRoot().equals(root.toString())
          || parent.status().equals("STARTED"))
        throw new IllegalArgumentException(
            "Completed review in the same provider/project root required");
    }
    db.sql(
            "INSERT INTO external_reviews(id,project,root,session,run,target,previous,status,created,completed,result,agent) VALUES(?,?,?,?,?,?,?,'STARTED',?,NULL,NULL,?)")
        .params(
            id,
            owner.projectId(),
            root.toString(),
            owner.conversationId(),
            owner.runId(),
            target,
            previous,
            clock.millis(),
            agent)
        .update();
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public void finish(String project, String id, ExternalAgentResult result) {
    var safe = safe(result);
    String json;
    try {
      json = mapper.writeValueAsString(safe);
    } catch (Exception error) {
      throw new IllegalStateException("Review serialization failed");
    }
    if (db.sql(
                "UPDATE external_reviews SET status=?,completed=?,result=? WHERE project=? AND id=? AND status='STARTED'")
            .params(safe.status().name(), clock.millis(), json, project, id)
            .update()
        != 1) throw new IllegalArgumentException("Review is missing or already terminal");
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Review get(String project, String id) {
    return db.sql("SELECT * FROM external_reviews WHERE project=? AND id=?")
        .params(project, id)
        .query(this::row)
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Review not found in this Project"));
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Review> list(String project) {
    return db.sql(
            "SELECT * FROM external_reviews WHERE project=? ORDER BY created DESC,id LIMIT 20")
        .param(project)
        .query(this::row)
        .list();
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Review> list(String project, String agent) {
    if (!Set.of("codex", "claude").contains(agent))
      throw new IllegalArgumentException("Unsupported review provider");
    return db.sql(
            "SELECT * FROM external_reviews WHERE project=? AND agent=? ORDER BY created DESC,id LIMIT 20")
        .params(project, agent)
        .query(this::row)
        .list();
  }

  /** 外部エージェントへの委譲で用いる行を返す。 */
  private Review row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
    String json = rs.getString("result");
    ExternalAgentResult result = null;
    if (json != null)
      try {
        result = mapper.readValue(json, ExternalAgentResult.class);
      } catch (Exception error) {
        throw new IllegalStateException("Stored review is invalid");
      }
    Long completed = rs.getObject("completed") == null ? null : rs.getLong("completed");
    return new Review(
        rs.getString("id"),
        rs.getString("project"),
        rs.getString("root"),
        rs.getString("session"),
        rs.getString("run"),
        rs.getString("target"),
        rs.getString("previous"),
        rs.getString("status"),
        Instant.ofEpochMilli(rs.getLong("created")),
        completed == null ? null : Instant.ofEpochMilli(completed),
        result,
        rs.getString("agent"));
  }

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  static ExternalAgentResult safe(ExternalAgentResult result) {
    boolean truncated =
        result.findings().size() > 24
            || result.warnings().size() > 12
            || length(result.summary()) > 2048
            || result.findings().stream()
                .anyMatch(
                    f ->
                        length(f.title()) > 256
                            || length(f.reason()) > 1024
                            || length(f.recommendation()) > 1024
                            || length(f.location()) > 512)
            || result.warnings().stream().anyMatch(s -> length(s) > 512);
    var findings =
        result.findings().stream()
            .limit(24)
            .map(
                f ->
                    new ExternalAgentFinding(
                        f.severity() == null ? ExternalAgentFinding.Severity.info : f.severity(),
                        bounded(f.title(), 256),
                        bounded(f.reason(), 1024),
                        bounded(f.recommendation(), 1024),
                        bounded(f.location(), 512)))
            .toList();
    var warnings =
        new ArrayList<>(
            result.warnings().stream()
                .limit(truncated ? 11 : 12)
                .map(s -> bounded(s, 512))
                .toList());
    if (truncated)
      warnings.add(
          "Saved review fields were truncated; inspect the review scope before drawing conclusions");
    var status =
        truncated && result.status() == ExternalAgentResult.Status.SUCCESS
            ? ExternalAgentResult.Status.SUCCESS_WITH_WARNINGS
            : result.status();
    return new ExternalAgentResult(
        status,
        bounded(result.summary(), 2048),
        findings,
        warnings,
        result.duration(),
        result.exitCode(),
        "",
        result.reviewId(),
        result.externalSessionId(),
        null,
        result.changeSetId(),
        null,
        result.materialReviewReport() == null
            ? null
            : bounded(result.materialReviewReport(), MaterialReviewReport.MAX_REPORT_CHARS));
  }

  /** 外部エージェントへの委譲で用いる長さを返す。 */
  private static int length(String text) {
    return text == null ? 0 : text.length();
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private static String bounded(String text, int limit) {
    return ExternalAgentDelegationService.bounded(text, limit);
  }
}
