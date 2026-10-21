package dev.mikoto2000.rei.episode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 経験の記録を保存・検索するための窓口。
 *
 * <p>Additive schema; revision publication and compare-and-set cursor advance share one
 * transaction.
 */
@Repository
public class EpisodeRepository {
  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新をまとめて確定するための窓口。 */
  private final TransactionTemplate transactions;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** 指定された値や利用先を使って、EpisodeRepositoryを初期化する。 */
  public EpisodeRepository(@Qualifier("memoryConsolidationDataSource") DataSource source) {
    db = JdbcClient.create(source);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transactions.execute(
        s -> {
          db.sql(
                  "CREATE TABLE IF NOT EXISTS episodes(id TEXT NOT NULL,revision TEXT NOT NULL,project_id TEXT NOT NULL,session_id TEXT NOT NULL,occurred_at TEXT NOT NULL,created_at TEXT NOT NULL,record TEXT NOT NULL,PRIMARY KEY(id,revision))")
              .update();
          db.sql(
                  "CREATE INDEX IF NOT EXISTS episode_project_time ON episodes(project_id,occurred_at)")
              .update();
          db.sql("CREATE INDEX IF NOT EXISTS episode_revision_owner ON episodes(id,project_id)")
              .update();
          db.sql("CREATE INDEX IF NOT EXISTS episode_session ON episodes(session_id)").update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS episode_dense_indexed(id TEXT PRIMARY KEY,revision TEXT NOT NULL)")
              .update();
          if (!db.sql("PRAGMA table_info(episode_dense_indexed)")
              .query((r, n) -> r.getString("name"))
              .list()
              .contains("generation"))
            db.sql(
                    "ALTER TABLE episode_dense_indexed ADD COLUMN generation TEXT NOT NULL DEFAULT 'default'")
                .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS episode_processing_checkpoints(session_id TEXT PRIMARY KEY,position INTEGER NOT NULL)")
              .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS episode_processing_leases(session_id TEXT PRIMARY KEY,owner TEXT NOT NULL,expires_at INTEGER NOT NULL)")
              .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS episode_sources(episode_id TEXT NOT NULL,revision TEXT NOT NULL,session_id TEXT NOT NULL,run_id TEXT NOT NULL,speaker TEXT NOT NULL,source_id TEXT NOT NULL DEFAULT '',PRIMARY KEY(episode_id,revision,session_id,run_id,speaker,source_id))")
              .update();
          if (!db.sql("PRAGMA table_info(episode_sources)")
              .query((r, n) -> r.getString("name"))
              .list()
              .contains("source_id"))
            db.sql("ALTER TABLE episode_sources ADD COLUMN source_id TEXT NOT NULL DEFAULT ''")
                .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS episode_relations(episode_id TEXT NOT NULL,target_id TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(episode_id,target_id,kind))")
              .update();
          db.sql(
                  "CREATE VIRTUAL TABLE IF NOT EXISTS episode_fts USING fts5(id UNINDEXED,revision UNINDEXED,content,tokenize='trigram')")
              .update();
          return null;
        });
  }

  /** 過去の経験の活用で用いる再開用の記録を返す。 */
  public long checkpoint(String session) {
    return db.sql("SELECT position FROM episode_processing_checkpoints WHERE session_id=?")
        .param(session)
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  /** 過去の経験の活用において、確保に該当するか判定する。 */
  public boolean acquire(String session, String worker) {
    return acquire(session, worker, 600);
  }

  /** 過去の経験の活用において、確保に該当するか判定する。 */
  public boolean acquire(String session, String worker, long seconds) {
    long now = java.time.Instant.now().getEpochSecond();
    return db.sql(
                "INSERT INTO episode_processing_leases VALUES(?,?,?) ON CONFLICT(session_id) DO UPDATE SET owner=excluded.owner,expires_at=excluded.expires_at WHERE expires_at<?")
            .params(session, worker, Math.addExact(now, seconds), now)
            .update()
        == 1;
  }

  /** 後続の処理が利用できるよう、経験の記録保存と検索の窓口を解放する。 */
  public void release(String session, String worker) {
    db.sql("DELETE FROM episode_processing_leases WHERE session_id=? AND owner=?")
        .params(session, worker)
        .update();
  }

  /** 後から参照できるよう、まとめた処理を保存する。 */
  public void saveBatch(String session, long expected, long next, List<Episode> episodes) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (expected < 0 || next < expected || episodes.size() > 50)
      throw new IllegalArgumentException("Invalid episode batch");
    transactions.execute(
        s -> {
          db.sql("INSERT OR IGNORE INTO episode_processing_checkpoints VALUES(?,0)")
              .param(session)
              .update();
          if (db.sql(
                      "UPDATE episode_processing_checkpoints SET position=? WHERE session_id=? AND position=?")
                  .params(next, session, expected)
                  .update()
              != 1) throw new IllegalStateException("Episode checkpoint changed; retry");
          for (var episode : episodes) {
            if (!episode.sessionId().equals(session))
              throw new IllegalArgumentException("Episode belongs to another session");
            var owner =
                db.sql("SELECT project_id FROM episodes WHERE id=? LIMIT 1")
                    .param(episode.id())
                    .query(String.class)
                    .optional();
            if (owner.isPresent() && !owner.get().equals(episode.projectId()))
              throw new IllegalArgumentException("Episode belongs to another project");
            String record = encode(episode);
            if (new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector()
                .containsSensitiveInfo(
                    episode.title()
                        + episode.summary()
                        + episode.claims().stream()
                            .map(Episode.Claim::text)
                            .reduce("", String::concat)))
              throw new IllegalArgumentException("Sensitive episode content");
            int inserted =
                db.sql("INSERT OR IGNORE INTO episodes VALUES(?,?,?,?,?,?,?)")
                    .params(
                        episode.id(),
                        episode.revision(),
                        episode.projectId(),
                        session,
                        EpisodeTimeRange.sortable(java.time.Instant.parse(episode.occurredAt())),
                        java.time.Instant.now().toString(),
                        record)
                    .update();
            if (inserted == 0) {
              String old =
                  db.sql("SELECT record FROM episodes WHERE id=? AND revision=?")
                      .params(episode.id(), episode.revision())
                      .query(String.class)
                      .single();
              if (!old.equals(record))
                throw new IllegalArgumentException("Immutable episode revision conflict");
              continue;
            }
            for (var claim : episode.claims())
              db.sql(
                      "INSERT OR IGNORE INTO episode_sources(episode_id,revision,session_id,run_id,speaker,source_id) VALUES(?,?,?,?,?,?)")
                  .params(
                      episode.id(),
                      episode.revision(),
                      session,
                      claim.runId(),
                      claim.speaker(),
                      Objects.toString(claim.sourceId(), ""))
                  .update();
            db.sql("INSERT INTO episode_fts VALUES(?,?,?)")
                .params(
                    episode.id(),
                    episode.revision(),
                    episode.title()
                        + " "
                        + episode.summary()
                        + " "
                        + episode.claims().stream()
                            .map(Episode.Claim::text)
                            .reduce("", (a, b) -> a + " " + b))
                .update();
          }
          return null;
        });
  }

  /** 対象を特定できるよう、経験の記録保存と検索の窓口を探す。 */
  public Optional<Episode> find(String id, String project) {
    return db.sql(
            "SELECT record FROM episodes WHERE id=? AND project_id=? ORDER BY rowid DESC LIMIT 1")
        .params(id, project)
        .query((r, n) -> decode(r.getString(1)))
        .optional();
  }

  /** 過去の経験の活用で用いる改訂一覧を返す。 */
  public List<Episode> revisions(String id, String project) {
    var rows =
        new ArrayList<>(
            db.sql(
                    "SELECT record FROM episodes WHERE id=? AND project_id=? ORDER BY rowid DESC LIMIT 100")
                .params(id, project)
                .query((r, n) -> decode(r.getString(1)))
                .list());
    Collections.reverse(rows);
    return List.copyOf(rows);
  }

  /**
   * 過去の経験の活用で受け渡す改訂番号ページを表す。
   *
   * @param items 項目一覧
   * @param nextCursor 次続きの取得位置
   */
  public record RevisionPage(List<Episode> items, String nextCursor) {}

  /** 過去の経験の活用で用いる改訂番号ページを返す。 */
  public RevisionPage revisionPage(String id, String project, String beforeRevision, int limit) {
    if (limit < 1 || limit > 10)
      throw new IllegalArgumentException("Revision page limit must be 1..10");
    long before =
        beforeRevision == null
            ? Long.MAX_VALUE
            : db.sql("SELECT rowid FROM episodes WHERE id=? AND project_id=? AND revision=?")
                .params(id, project, beforeRevision)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown revision cursor"));
    var rows =
        db.sql(
                "SELECT record FROM episodes WHERE id=? AND project_id=? AND rowid<? ORDER BY rowid DESC LIMIT ?")
            .params(id, project, before, limit + 1)
            .query((r, n) -> decode(r.getString(1)))
            .list();
    var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
    return new RevisionPage(items, rows.size() > limit ? items.getLast().revision() : null);
  }

  /** 過去の経験の活用で用いる改訂番号を返す。 */
  public Optional<Episode> revision(String id, String project, String revision) {
    return db.sql("SELECT record FROM episodes WHERE id=? AND project_id=? AND revision=?")
        .params(id, project, revision)
        .query((r, n) -> decode(r.getString(1)))
        .optional();
  }

  /** 過去の経験の活用で用いる待機中ベクトルによるを返す。 */
  public List<Episode> pendingDense(String session, int limit) {
    return pendingDense(session, limit, "default");
  }

  /** 過去の経験の活用で用いる待機中ベクトルによるを返す。 */
  public List<Episode> pendingDense(String session, int limit, String generation) {
    if (limit < 1 || limit > 50)
      throw new IllegalArgumentException("Dense batch limit must be 1..50");
    return db.sql(
            "SELECT e.record FROM episodes e LEFT JOIN episode_dense_indexed d ON d.id=e.id AND d.revision=e.revision AND d.generation=? WHERE e.session_id=? AND d.id IS NULL AND e.rowid=(SELECT MAX(x.rowid) FROM episodes x WHERE x.id=e.id) ORDER BY e.rowid LIMIT ?")
        .params(generation, session, limit)
        .query((r, n) -> decode(r.getString(1)))
        .list();
  }

  /** 後続の判断に現在の状態を反映するため、ベクトルによるの状態を記録する。 */
  public void markDense(Episode episode) {
    markDense(episode, "default");
  }

  /** 過去の経験の活用において、状態の記録ベクトルによるに該当するか判定する。 */
  public boolean markDense(Episode episode, String generation) {
    return db.sql(
                "INSERT INTO episode_dense_indexed(id,revision,generation) SELECT id,revision,? FROM episodes WHERE id=? AND revision=? AND rowid=(SELECT MAX(rowid) FROM episodes WHERE id=?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,generation=excluded.generation")
            .params(generation, episode.id(), episode.revision(), episode.id())
            .update()
        == 1;
  }

  /** 過去の経験の活用に関するキャッシュの無効化ベクトルによるを処理する。 */
  public void invalidateDense(String id) {
    db.sql("DELETE FROM episode_dense_indexed WHERE id=?").param(id).update();
  }

  /** 過去の経験の活用において、ベクトルによるの有無を判定する。 */
  public boolean hasDense(String project, String generation) {
    return db.sql(
                "SELECT EXISTS(SELECT 1 FROM episodes e JOIN episode_dense_indexed d ON d.id=e.id AND d.revision=e.revision AND d.generation=? WHERE e.project_id=? AND e.rowid=(SELECT MAX(x.rowid) FROM episodes x WHERE x.id=e.id))")
            .params(generation, project)
            .query(Integer.class)
            .single()
        == 1;
  }

  /** 過去の経験の活用に関するリンク記憶を処理する。 */
  public void linkMemory(String project, String session, List<String> runs, String memory) {
    String type =
        db.sql("SELECT type FROM memories WHERE id=? AND (project_id=? OR scope='GLOBAL')")
            .params(memory, project)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Unknown or foreign related memory"));
    transactions.execute(
        s -> {
          for (String run : runs)
            db.sql(
                    "INSERT OR IGNORE INTO episode_relations SELECT DISTINCT e.id,?,? FROM episodes e JOIN episode_sources s ON s.episode_id=e.id AND s.revision=e.revision WHERE e.project_id=? AND s.session_id=? AND s.run_id=?")
                .params(memory, type, project, session, run)
                .update();
          return null;
        });
  }

  /** 過去の経験の活用で用いる関連一覧を返す。 */
  public List<Map<String, String>> relations(String id, String project) {
    return db.sql(
            "SELECT r.target_id,r.kind FROM episode_relations r WHERE r.episode_id=? AND EXISTS(SELECT 1 FROM episodes e WHERE e.id=r.episode_id AND e.project_id=?) LIMIT 32")
        .params(id, project)
        .query((r, n) -> Map.of("targetId", r.getString(1), "kind", r.getString(2)))
        .list();
  }

  /**
   * 過去の経験の活用に関するリンク作業文脈を処理する。
   *
   * <p>Only actual persisted Work Context items supplied by WorkContextService can be linked.
   */
  public void linkWorkContext(dev.mikoto2000.rei.workcontext.WorkContext context, String session) {
    transactions.execute(
        s -> {
          for (var item : context.items().stream().limit(100).toList())
            for (var source : item.evidence().stream().limit(32).toList()) {
              if (!session.equals(source.sessionId()) || source.runId() == null) continue;
              db.sql(
                      "INSERT OR IGNORE INTO episode_relations SELECT DISTINCT e.id,?,'WORK_CONTEXT' FROM episodes e JOIN episode_sources s ON s.episode_id=e.id AND s.revision=e.revision WHERE e.project_id=? AND s.session_id=? AND s.run_id=?")
                  .params(item.id(), context.projectId(), session, source.runId())
                  .update();
            }
          return null;
        });
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<Episode> search(String query, String project, int limit) {
    return search(query, project, limit, EpisodeTimeRange.of(null, null));
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<Episode> search(String query, String project, int limit, EpisodeTimeRange range) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (limit < 1 || limit > 100 || query == null || query.length() > 2000)
      throw new IllegalArgumentException("Invalid episode query");
    var words =
        dev.mikoto2000.rei.memory.service.MemorySearchTerms.of(query).stream()
            .filter(w -> w.codePointCount(0, w.length()) >= 3)
            .map(w -> "\"" + w + "\"")
            .toList();
    if (words.isEmpty()) return List.of();
    var args = new ArrayList<Object>();
    args.add(String.join(" OR ", words));
    args.add(project);
    String period = "";
    if (range.from() != null) {
      period += " AND e.occurred_at>=?";
      args.add(EpisodeTimeRange.sortable(range.from()));
    }
    if (range.until() != null) {
      period += range.exclusiveEnd() ? " AND e.occurred_at<?" : " AND e.occurred_at<=?";
      args.add(EpisodeTimeRange.sortable(range.until()));
    }
    args.add(limit);
    return db.sql(
            "SELECT e.record FROM episode_fts f JOIN episodes e ON e.id=f.id AND e.revision=f.revision WHERE episode_fts MATCH ? AND e.project_id=? AND e.rowid=(SELECT MAX(x.rowid) FROM episodes x WHERE x.id=e.id)"
                + period
                + " ORDER BY bm25(episode_fts) LIMIT ?")
        .params(args)
        .query((r, n) -> decode(r.getString(1)))
        .list();
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(Episode episode) {
    try {
      return json.writeValueAsString(episode);
    } catch (Exception e) {
      throw new IllegalArgumentException("Cannot encode episode", e);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private Episode decode(String record) {
    try {
      return json.readValue(record, Episode.class);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot decode episode", e);
    }
  }
}
