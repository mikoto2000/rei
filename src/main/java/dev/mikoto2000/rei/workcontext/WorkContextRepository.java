package dev.mikoto2000.rei.workcontext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 作業文脈を保存・検索するための窓口。
 *
 * <p>Atomic current pointer and immutable snapshots; optimistic revision checks also protect other
 * processes.
 */
@Repository
public class WorkContextRepository {
  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新をまとめて確定するための窓口。 */
  private final TransactionTemplate transactions;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 指定された値や利用先を使って、WorkContextRepositoryを初期化する。 */
  public WorkContextRepository(@Qualifier("memoryConsolidationDataSource") DataSource source) {
    db = JdbcClient.create(source);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    db.sql(
            "CREATE TABLE IF NOT EXISTS work_context_heads(project_id TEXT PRIMARY KEY,revision INTEGER NOT NULL)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS work_context_revisions(project_id TEXT NOT NULL,revision INTEGER NOT NULL,snapshot TEXT NOT NULL,PRIMARY KEY(project_id,revision))")
        .update();
  }

  /** 作業文脈の引き継ぎで用いる現在を返す。 */
  public Optional<WorkContext> current(String project) {
    return db.sql(
            "SELECT r.snapshot FROM work_context_revisions r JOIN work_context_heads h ON r.project_id=h.project_id AND r.revision=h.revision WHERE r.project_id=?")
        .param(project)
        .query(String.class)
        .optional()
        .map(this::decode);
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  public List<WorkContext> history(String project, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
    return db
        .sql(
            "SELECT snapshot FROM work_context_revisions WHERE project_id=? ORDER BY revision DESC LIMIT ?")
        .params(project, limit)
        .query(String.class)
        .list()
        .stream()
        .map(this::decode)
        .toList();
  }

  /** 作業文脈の引き継ぎで用いる改訂番号を返す。 */
  public Optional<WorkContext> revision(String project, long revision) {
    return db.sql("SELECT snapshot FROM work_context_revisions WHERE project_id=? AND revision=?")
        .params(project, revision)
        .query(String.class)
        .optional()
        .map(this::decode);
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public void save(WorkContext context, long expected) {
    if (context.revision() != expected + 1) throw new IllegalArgumentException("Invalid revision");
    String snapshot = encode(context);
    transactions.executeWithoutResult(
        tx -> {
          checkCancellation();
          db.sql("INSERT OR IGNORE INTO work_context_heads(project_id,revision) VALUES(?,0)")
              .param(context.projectId())
              .update();
          if (db.sql("UPDATE work_context_heads SET revision=? WHERE project_id=? AND revision=?")
                  .params(context.revision(), context.projectId(), expected)
                  .update()
              != 1) throw new ConcurrentModificationException("Work Context changed; retry update");
          db.sql("INSERT INTO work_context_revisions(project_id,revision,snapshot) VALUES(?,?,?)")
              .params(context.projectId(), context.revision(), snapshot)
              .update();
          checkCancellation();
        });
  }

  /** 後続の操作を進められるか判断するため、キャンセルを確認する。 */
  static void checkCancellation() {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private WorkContext decode(String text) {
    try {
      return json.readValue(text, WorkContext.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot read Work Context", e);
    }
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(WorkContext context) {
    try {
      return json.writeValueAsString(context);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot serialize Work Context", e);
    }
  }
}
