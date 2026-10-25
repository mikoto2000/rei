package dev.mikoto2000.rei.attention;

import dev.mikoto2000.rei.event.AgentEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 注意を保存・検索するための窓口。
 *
 * <p>Persistent human inbox; acknowledging an item never grants permissions or dispatches a run.
 */
@Repository
public class AttentionRepository {
  /** Idempotent local ingress; the existing delivery service keeps its policy and destination. */
  public Item createSecretary(dev.mikoto2000.rei.secretary.SecretaryNotifications.Notice notice) {
    String kind = notice.global() ? "SECRETARY_GLOBAL_INBOX" : "SECRETARY_" + notice.destination();
    String id =
        UUID.nameUUIDFromBytes(
                ("secretary:" + notice.projectId() + ":" + notice.id())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    db.sql("INSERT OR IGNORE INTO agent_attention VALUES(?,?,?,? ,?,?,?,'OPEN',?)")
        .params(
            id,
            notice.projectId(),
            "",
            "",
            kind,
            notice.id(),
            notice.message(),
            notice.created().toEpochMilli())
        .update();
    return get(notice.projectId(), id);
  }

  /**
   * 注意の管理で受け渡す項目を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param runId 実行の識別子
   * @param kind 種類
   * @param reference 参照先
   * @param message メッセージ
   * @param status 状態
   * @param createdAt 作成日時
   */
  public record Item(
      String id,
      String projectId,
      String sessionId,
      String runId,
      String kind,
      String reference,
      String message,
      String status,
      Instant createdAt) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  private final org.springframework.transaction.support.TransactionTemplate transactions;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、AttentionRepositoryを初期化する。 */
  public AttentionRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    db = JdbcClient.create(source);
    transactions =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_attention(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,kind TEXT NOT NULL,reference TEXT NOT NULL,message TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(project,session,run,kind,reference))")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS agent_attention_open ON agent_attention(project,status,created)")
        .update();
  }

  /** 行。 */
  private static final org.springframework.jdbc.core.RowMapper<Item> ROW =
      (rs, n) ->
          new Item(
              rs.getString("id"),
              rs.getString("project"),
              rs.getString("session").isEmpty() ? null : rs.getString("session"),
              rs.getString("run").isEmpty() ? null : rs.getString("run"),
              rs.getString("kind"),
              rs.getString("reference"),
              rs.getString("message"),
              rs.getString("status"),
              Instant.ofEpochMilli(rs.getLong("created")));

  /** 指定された内容から新しい対象を作成する。 */
  public Optional<Item> create(AgentEvent source, String kind, String reference, String message) {
    return create(source, kind, reference, message, false);
  }

  /**
   * 新たな対象を利用できるよう、依存条件を作成する。
   *
   * <p>Empty stored run denotes an owned dependency fact; the public/API Run ID stays null.
   */
  public Optional<Item> createDependency(
      AgentEvent source, String kind, String reference, String message) {
    if (!(source.payload() instanceof dev.mikoto2000.rei.event.DependencyStatusPayload payload)
        || payload.dependencyId() == null
        || !payload.dependencyId().equals(reference)
        || !reference.equals(source.correlationId())
        || source.runId() != null) return Optional.empty();
    return create(source, kind, reference, message, true);
  }

  /** 新たな対象を利用できるよう、Git連携先を作成する。 */
  public Optional<Item> createGitHub(AgentEvent source, String kind, String message) {
    if (!(source.payload() instanceof dev.mikoto2000.rei.event.GitHubLifecyclePayload payload)
        || source.runId() != null
        || payload.sourceId() == null
        || payload.fact() == null
        || !payload.sourceId().equals(payload.fact().sourceId())
        || !payload.sourceId().equals(source.correlationId())
        || !source.type().name().equals("GITHUB_" + payload.fact().type())) return Optional.empty();
    return create(source, kind, source.id(), message, true);
  }

  /** 指定された内容から新しい対象を作成する。 */
  private Optional<Item> create(
      AgentEvent source, String kind, String reference, String message, boolean dependency) {
    if (source.projectId() == null
        || source.projectId().isBlank()
        || source.sessionId() == null
        || source.sessionId().isBlank()
        || (!dependency && (source.runId() == null || source.runId().isBlank())))
      return Optional.empty();
    String id = UUID.randomUUID().toString();
    int inserted =
        db.sql("INSERT OR IGNORE INTO agent_attention VALUES(?,?,?,?,?,?,?,'OPEN',?)")
            .params(
                id,
                source.projectId(),
                source.sessionId(),
                dependency ? "" : source.runId(),
                kind,
                reference,
                message,
                clock.millis())
            .update();
    return inserted == 1 ? Optional.of(get(source.projectId(), id)) : Optional.empty();
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public void hold(String project, String id) {
    db.sql("UPDATE agent_attention SET status='HELD' WHERE project=? AND id=? AND status='OPEN'")
        .params(project, id)
        .update();
  }

  public List<String> heldProjects() {
    return db.sql(
            "SELECT DISTINCT project FROM agent_attention WHERE status='HELD' ORDER BY project LIMIT 16")
        .query(String.class)
        .list();
  }

  /** Reuse the inbox state, atomically moving a bounded group into one persistent digest. */
  public Optional<Item> releaseHeld(String project) {
    return transactions.execute(
        status -> {
          var held =
              db.sql(
                      "SELECT * FROM agent_attention WHERE project=? AND status='HELD' ORDER BY CASE kind WHEN 'APPROVAL_REQUIRED' THEN 0 WHEN 'RUN_FAILED' THEN 1 ELSE 2 END,created,id LIMIT 8")
                  .param(project)
                  .query(ROW)
                  .list();
          var existing = held.stream().filter(i -> i.kind().equals("LIFE_DIGEST")).findFirst();
          if (existing.isPresent()) {
            var digest = existing.get();
            int changed =
                db.sql(
                        "UPDATE agent_attention SET status='OPEN' WHERE project=? AND id=? AND status='HELD'")
                    .params(project, digest.id())
                    .update();
            return changed == 1 ? Optional.of(get(project, digest.id())) : Optional.empty();
          }
          var claimed = new java.util.ArrayList<String>();
          for (var item : held)
            if (db.sql(
                        "UPDATE agent_attention SET status='OPEN' WHERE project=? AND id=? AND status='HELD'")
                    .params(project, item.id())
                    .update()
                == 1) claimed.add(item.id());
          if (claimed.isEmpty()) return Optional.empty();
          String reference = String.join(",", claimed.stream().sorted().toList());
          String id =
              UUID.nameUUIDFromBytes(
                      ("life-digest:" + project + ":" + reference)
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8))
                  .toString();
          db.sql("INSERT OR IGNORE INTO agent_attention VALUES(?,?,? ,? ,?,?,?,'OPEN',?)")
              .params(
                  id,
                  project,
                  "",
                  "",
                  "LIFE_DIGEST",
                  id,
                  "保留通知 " + claimed.size() + "件。/attention list で判断待ち・失敗・完了を確認してください。",
                  clock.millis())
              .update();
          return Optional.of(get(project, id));
        });
  }

  public List<Item> list(String project) {
    return db.sql(
            "SELECT * FROM agent_attention WHERE project=? AND status IN ('OPEN','HELD') ORDER BY created,id LIMIT 256")
        .param(project)
        .query(ROW)
        .list();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Item get(String project, String id) {
    return db.sql("SELECT * FROM agent_attention WHERE project=? AND id=?")
        .params(project, id)
        .query(ROW)
        .optional()
        .orElseThrow(
            () -> new IllegalArgumentException("Attention item not found in this project"));
  }

  /** 注意の管理に関するacknowledgeを処理する。 */
  public void acknowledge(String project, String id) {
    get(project, id);
    db.sql(
            "UPDATE agent_attention SET status='ACKNOWLEDGED' WHERE project=? AND id=? AND status IN ('OPEN','HELD')")
        .params(project, id)
        .update();
  }
}
