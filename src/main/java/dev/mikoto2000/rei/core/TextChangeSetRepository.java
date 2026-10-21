package dev.mikoto2000.rei.core;

import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * テキスト変更集合を保存・検索するための窓口。
 *
 * <p>Durable single-file proposals and one-way application claims. No automatic recovery writes.
 */
@Repository
public class TextChangeSetRepository {
  /**
   * 共通の実行基盤で受け渡す保存済みを表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param root 作業ルート
   * @param path パス
   * @param baseline 比較の基準
   * @param proposed 提案内容
   * @param baselineHash 比較の基準ハッシュ値
   * @param proposedHash 提案内容ハッシュ値
   * @param proposalHash 提案ハッシュ値
   * @param status 状態
   * @param createdAt 作成日時
   */
  record Saved(
      String id,
      String projectId,
      String root,
      String path,
      String baseline,
      String proposed,
      String baselineHash,
      String proposedHash,
      String proposalHash,
      String status,
      Instant createdAt) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 利用権一覧。 */
  private final PersistedReceiptLease leases;

  /** 指定された値や利用先を使って、TextChangeSetRepositoryを初期化する。 */
  public TextChangeSetRepository(@Qualifier("memoryConsolidationDataSource") DataSource source) {
    db = JdbcClient.create(source);
    db.sql(
            "CREATE TABLE IF NOT EXISTS text_change_sets(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,root TEXT NOT NULL,path TEXT NOT NULL,baseline TEXT NOT NULL,proposed TEXT NOT NULL,baseline_hash TEXT NOT NULL,proposed_hash TEXT NOT NULL,proposal_hash TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS text_change_set_apply_guards(project_id TEXT NOT NULL,id TEXT NOT NULL,guard TEXT NOT NULL,PRIMARY KEY(project_id,id))")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS text_document_change_sets(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,payload TEXT NOT NULL,sha TEXT NOT NULL,status TEXT NOT NULL,phase TEXT NOT NULL DEFAULT 'PROPOSED',stages TEXT NOT NULL DEFAULT '[]',warnings TEXT NOT NULL DEFAULT '[]',pid INTEGER,process_start TEXT,heartbeat TEXT,recoveries INTEGER NOT NULL DEFAULT 0)")
        .update();
    db.sql(
            "CREATE UNIQUE INDEX IF NOT EXISTS text_document_active_root ON text_document_change_sets(root) WHERE status IN ('APPLYING','ROLLING_BACK','UNKNOWN')")
        .update();
    leases = new PersistedReceiptLease(source, "text_change_sets", java.time.Clock.systemUTC());
  }

  /** 共通の実行基盤で用いる保存済み文書データベースを返す。 */
  org.springframework.jdbc.core.simple.JdbcClient documentsDb() {
    return db;
  }

  /** 共通の実行基盤に関する保護診断済みを処理する。 */
  void protectDiagnosed(String project, String id) {
    db.sql("INSERT INTO text_change_set_apply_guards VALUES(?,?,'DIAGNOSED_REPAIR')")
        .params(project, id)
        .update();
  }

  /** 指定された変更を有効にするため、利用条件の確認を反映する。 */
  String applyGuard(String project, String id) {
    return db.sql("SELECT guard FROM text_change_set_apply_guards WHERE project_id=? AND id=?")
        .params(project, id)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** 共通の実行基盤で用いるactivateを返す。 */
  PersistedReceiptLease.AutoCloseableLease activate(String id) {
    return leases.activate(id);
  }

  /** 共通の実行基盤に関する生存確認を処理する。 */
  void heartbeat(String id) {
    leases.heartbeat(id);
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  void save(Saved saved) {
    // Bounded per-owner history. Pending/uncertain proposals are never silently evicted.
    db.sql(
            "DELETE FROM text_change_sets WHERE project_id=? AND status IN ('APPLIED','STALE','DISCARDED','RECONCILED') AND NOT EXISTS(SELECT 1 FROM text_change_set_apply_guards g WHERE g.project_id=text_change_sets.project_id AND g.id=text_change_sets.id) AND id NOT IN (SELECT id FROM text_change_sets WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 100)")
        .params(saved.projectId(), saved.projectId())
        .update();
    int added =
        db.sql(
                "INSERT INTO text_change_sets(id,project_id,root,path,baseline,proposed,baseline_hash,proposed_hash,proposal_hash,status,created_at) SELECT ?,?,?,?,?,?,?,?,?,?,? WHERE (SELECT count(*) FROM text_change_sets WHERE project_id=?)<128")
            .params(
                saved.id(),
                saved.projectId(),
                saved.root(),
                saved.path(),
                saved.baseline(),
                saved.proposed(),
                saved.baselineHash(),
                saved.proposedHash(),
                saved.proposalHash(),
                saved.status(),
                saved.createdAt().toString(),
                saved.projectId())
            .update();
    if (added != 1)
      throw new IllegalStateException("Change Set capacity reached; inspect existing proposals");
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  Saved get(String project, String id) {
    var saved = load(project, id);
    if (saved.status().equals("APPLYING")) {
      leases.reconcile(id);
      return load(project, id);
    }
    return saved;
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  private Saved load(String project, String id) {
    return peek(project, id);
  }

  /** 共通の実行基盤で用いる次の対象の確認を返す。 */
  Saved peek(String project, String id) {
    return db.sql("SELECT * FROM text_change_sets WHERE project_id=? AND id=?")
        .params(project, id)
        .query(
            (row, n) ->
                new Saved(
                    row.getString("id"),
                    row.getString("project_id"),
                    row.getString("root"),
                    row.getString("path"),
                    row.getString("baseline"),
                    row.getString("proposed"),
                    row.getString("baseline_hash"),
                    row.getString("proposed_hash"),
                    row.getString("proposal_hash"),
                    row.getString("status"),
                    Instant.parse(row.getString("created_at"))))
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Unknown Change Set in this Project"));
  }

  /** 共通の実行基盤において、状態の遷移に該当するか判定する。 */
  boolean transition(String project, String id, String before, String after) {
    if (before.equals("PROPOSED") && after.equals("APPLYING"))
      return db.sql(
                  "UPDATE text_change_sets SET status=? WHERE project_id=? AND id=? AND status=? AND NOT EXISTS (SELECT 1 FROM text_change_sets s WHERE s.root=text_change_sets.root AND s.id<>text_change_sets.id AND s.status IN ('APPLYING','FAILED_UNCERTAIN','UNKNOWN')) AND NOT EXISTS (SELECT 1 FROM text_document_change_sets d WHERE d.root=text_change_sets.root AND d.status IN ('APPLYING','ROLLING_BACK','UNKNOWN'))")
              .params(after, project, id, before)
              .update()
          == 1;
    return db.sql("UPDATE text_change_sets SET status=? WHERE project_id=? AND id=? AND status=?")
            .params(after, project, id, before)
            .update()
        == 1;
  }
}
