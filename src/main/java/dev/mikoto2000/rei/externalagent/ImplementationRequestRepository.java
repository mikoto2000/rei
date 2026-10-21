package dev.mikoto2000.rei.externalagent;

import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 実装要求を保存・検索するための窓口。
 *
 * <p>Exact immutable specifications plus a permanent SQLite one-shot execution claim.
 */
@Repository
public class ImplementationRequestRepository {
  /**
   * 外部エージェントへの委譲で受け渡す要求を表す。
   *
   * @param requestId 要求の識別子
   * @param specificationVersion 仕様形式のバージョン
   * @param specificationSha256 仕様内容の同一性を照合するSHA-256ハッシュ
   * @param projectId 所属プロジェクトの識別子
   * @param projectRoot 作業対象のルートディレクトリ
   * @param sessionId 会話の識別子
   * @param originRunId 起点実行の識別子
   * @param originUserMessageId 起点利用者メッセージ識別子
   * @param sourceType 取得元種別
   * @param provider 提供元
   * @param baseCommit 基準確定
   * @param previousRequestId 前回要求の識別子
   * @param canonicalSpecification 正規仕様
   * @param executionEnvelope 実行共通の通知属性
   * @param policyDecision 判断基準判断結果
   * @param authorizationId 権限の確認識別子
   * @param executionStatus 実行状態
   * @param receiptId 受付記録識別子
   * @param result 結果
   * @param createdAt 作成日時
   * @param updatedAt 更新日時
   */
  public record Request(
      String requestId,
      int specificationVersion,
      String specificationSha256,
      String projectId,
      String projectRoot,
      String sessionId,
      String originRunId,
      String originUserMessageId,
      String sourceType,
      String provider,
      String baseCommit,
      String previousRequestId,
      String canonicalSpecification,
      String executionEnvelope,
      String policyDecision,
      String authorizationId,
      String executionStatus,
      String receiptId,
      String result,
      Instant createdAt,
      Instant updatedAt) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、ImplementationRequestRepositoryを初期化する。 */
  public ImplementationRequestRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    db = JdbcClient.create(source);
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS implementation_requests(id TEXT PRIMARY KEY,version INTEGER NOT NULL,hash TEXT NOT NULL,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,origin_run TEXT NOT NULL,origin_message TEXT NOT NULL,source TEXT NOT NULL,provider TEXT NOT NULL,base TEXT NOT NULL,previous TEXT,spec TEXT NOT NULL,envelope TEXT NOT NULL,policy TEXT NOT NULL,authorization TEXT,status TEXT NOT NULL,receipt TEXT,result TEXT,created INTEGER NOT NULL,updated INTEGER NOT NULL,claim_owner TEXT,UNIQUE(project,session,origin_run,hash,previous))")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS implementation_acceptance_evaluations(sequence INTEGER PRIMARY KEY AUTOINCREMENT,request TEXT NOT NULL,spec_hash TEXT NOT NULL,receipt TEXT NOT NULL,patch_hash TEXT NOT NULL,evaluations TEXT NOT NULL,created INTEGER NOT NULL)")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS implementation_requests_owner ON implementation_requests(project,session,created)")
        .update();
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public void save(Request r) {
    db.sql(
            "INSERT INTO implementation_requests(id,version,hash,project,root,session,origin_run,origin_message,source,provider,base,previous,spec,envelope,policy,authorization,status,receipt,result,created,updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        .params(
            r.requestId(),
            r.specificationVersion(),
            r.specificationSha256(),
            r.projectId(),
            r.projectRoot(),
            r.sessionId(),
            r.originRunId(),
            r.originUserMessageId(),
            r.sourceType(),
            r.provider(),
            r.baseCommit(),
            r.previousRequestId(),
            r.canonicalSpecification(),
            r.executionEnvelope(),
            r.policyDecision(),
            r.authorizationId(),
            r.executionStatus(),
            r.receiptId(),
            r.result(),
            r.createdAt().toEpochMilli(),
            r.updatedAt().toEpochMilli())
        .update();
  }

  /** 対象を特定できるよう、実装要求保存と検索の窓口を探す。 */
  public java.util.Optional<Request> find(
      String project, String session, String run, String hash, String previous) {
    return db.sql(
            "SELECT * FROM implementation_requests WHERE project=? AND session=? AND origin_run=? AND hash=? AND previous IS ? ORDER BY created LIMIT 1")
        .params(project, session, run, hash, previous)
        .query(this::row)
        .optional();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Request get(String id) {
    return db.sql("SELECT * FROM implementation_requests WHERE id=?")
        .param(id)
        .query(this::row)
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Implementation request not found"));
  }

  /** 対象を処理する担当を決めるため、実装要求保存と検索の窓口の利用権を確保する。 */
  public boolean claim(String id, String instance) {
    return db.sql(
                "UPDATE implementation_requests SET status='EXECUTING',receipt=id,claim_owner=?,updated=? WHERE id=? AND status IN ('AUTHORIZED','AWAITING_APPROVAL') AND claim_owner IS NULL")
            .params(instance, clock.millis(), id)
            .update()
        == 1;
  }

  /** 対象を処理する担当を決めるため、所有者の利用権を確保する。 */
  public String claimOwner(String id) {
    return db.sql("SELECT claim_owner FROM implementation_requests WHERE id=?")
        .param(id)
        .query(String.class)
        .optional()
        .orElse("");
  }

  /** 外部エージェントへの委譲に関する権限の確認を処理する。 */
  public void authorization(String id, String decision, String authorization, String status) {
    db.sql(
            "UPDATE implementation_requests SET policy=?,authorization=?,status=?,updated=? WHERE id=? AND claim_owner IS NULL")
        .params(decision, authorization, status, clock.millis(), id)
        .update();
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public void finish(String id, String status, String receipt, String result) {
    if (db.sql(
                "UPDATE implementation_requests SET status=?,receipt=?,result=?,updated=? WHERE id=? AND status IN ('EXECUTING','VERIFYING','UNKNOWN')")
            .params(status, receipt, result, clock.millis(), id)
            .update()
        != 1) {
      var saved = get(id);
      if (!saved.executionStatus().equals(status)
          || !java.util.Objects.equals(saved.receiptId(), receipt)
          || !java.util.Objects.equals(saved.result(), result))
        throw new IllegalStateException(
            "Implementation request already has a different terminal result");
    }
  }

  /** 後から参照できるよう、評価一覧を保存する。 */
  public void saveEvaluations(
      String request, String specificationHash, String receipt, String patch, String evaluations) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (evaluations.length() > 32768)
      throw new IllegalArgumentException("Evaluation exceeds 32 KiB");
    db.sql(
            "INSERT INTO implementation_acceptance_evaluations(request,spec_hash,receipt,patch_hash,evaluations,created) VALUES(?,?,?,?,?,?)")
        .params(request, specificationHash, receipt, patch, evaluations, clock.millis())
        .update();
  }

  /** 外部エージェントへの委譲で用いる評価一覧を返す。 */
  public java.util.Optional<String> evaluations(
      String request, String specificationHash, String receipt, String patch) {
    return db.sql(
            "SELECT evaluations FROM implementation_acceptance_evaluations WHERE request=? AND spec_hash=? AND receipt=? AND patch_hash=? ORDER BY sequence DESC LIMIT 1")
        .params(request, specificationHash, receipt, patch)
        .query(String.class)
        .optional();
  }

  /** 外部エージェントへの委譲で用いる行を返す。 */
  private Request row(java.sql.ResultSet r, int index) throws java.sql.SQLException {
    return new Request(
        r.getString("id"),
        r.getInt("version"),
        r.getString("hash"),
        r.getString("project"),
        r.getString("root"),
        r.getString("session"),
        r.getString("origin_run"),
        r.getString("origin_message"),
        r.getString("source"),
        r.getString("provider"),
        r.getString("base"),
        r.getString("previous"),
        r.getString("spec"),
        r.getString("envelope"),
        r.getString("policy"),
        r.getString("authorization"),
        r.getString("status"),
        r.getString("receipt"),
        r.getString("result"),
        Instant.ofEpochMilli(r.getLong("created")),
        Instant.ofEpochMilli(r.getLong("updated")));
  }
}
