package dev.mikoto2000.rei.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AttentionRequiredPayload;
import dev.mikoto2000.rei.event.GitHubLifecyclePayload;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Git連携先事実を保存・検索するための窓口。
 *
 * <p>The receipt, facts, inbox and trigger transitions share one database transaction. No execution
 * here.
 */
public final class GitHubFactRepository {
  /**
   * GitHubとの連携で受け渡す受付記録を表す。
   *
   * @param deliveryId 配信識別子
   * @param status 状態
   * @param duplicate 重複の有無
   * @param matched 一致した
   */
  public record Receipt(String deliveryId, String status, boolean duplicate, int matched) {}

  /**
   * GitHubとの連携で受け渡す自分が所有する対象事実を表す。
   *
   * @param id 識別子
   * @param deliveryId 配信識別子
   * @param project プロジェクト
   * @param sessionId 会話の識別子
   * @param fact 事実
   */
  public record OwnedFact(
      String id, String deliveryId, ProjectContext project, String sessionId, GitHubFact fact) {
    /** GitHubとの連携で用いるイベントを返す。 */
    public AgentEvent event(Instant receivedAt) {
      return new AgentEvent(
          id,
          0,
          fact.occurredAt().isAfter(receivedAt) ? receivedAt : fact.occurredAt(),
          AgentEventType.valueOf("GITHUB_" + fact.type()),
          1,
          sessionId,
          null,
          null,
          fact.sourceId(),
          null,
          new GitHubLifecyclePayload(deliveryId, fact.sourceId(), fact),
          project.id());
    }
  }

  /**
   * GitHubとの連携で受け渡す表示内容を表す。
   *
   * @param id 識別子
   * @param deliveryId 配信識別子
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param sourceId 取得元識別子
   * @param fact 事実
   * @param receivedAt 受信日時
   */
  public record View(
      String id,
      String deliveryId,
      String projectId,
      String sessionId,
      String sourceId,
      GitHubFact fact,
      Instant receivedAt) {}

  /**
   * GitHubとの連携で受け渡す通知を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param root 作業ルート
   * @param sessionId 会話の識別子
   * @param parentId 親の識別子
   * @param kind 種類
   * @param message メッセージ
   * @param createdAt 作成日時
   */
  public record Notification(
      String id,
      String projectId,
      String root,
      String sessionId,
      String parentId,
      String kind,
      String message,
      Instant createdAt) {
    /** GitHubとの連携で用いるイベントを返す。 */
    public AgentEvent event() {
      return new AgentEvent(
          UUID.nameUUIDFromBytes(
                  ("github-attention:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .toString(),
          0,
          createdAt,
          AgentEventType.ATTENTION_REQUIRED,
          1,
          sessionId,
          null,
          null,
          id,
          parentId,
          new AttentionRequiredPayload(id, kind, message),
          projectId);
    }
  }

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新をまとめて確定するための窓口。 */
  private final TransactionTemplate tx;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 指定された値や利用先を使って、GitHubFactRepositoryを初期化する。 */
  public GitHubFactRepository(DataSource source, Clock clock) {
    db = JdbcClient.create(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS github_webhook_deliveries(id TEXT PRIMARY KEY,digest TEXT NOT NULL UNIQUE,event TEXT NOT NULL,status TEXT NOT NULL,matched INTEGER NOT NULL,received TEXT NOT NULL)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS github_webhook_facts(id TEXT PRIMARY KEY,delivery TEXT NOT NULL,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,source TEXT NOT NULL,type TEXT NOT NULL,repository TEXT NOT NULL,pr INTEGER,fact TEXT NOT NULL,received TEXT NOT NULL)")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS github_facts_owner ON github_webhook_facts(project,root,session,source,type)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS github_notification_outbox(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,parent TEXT NOT NULL,kind TEXT NOT NULL,message TEXT NOT NULL,created TEXT NOT NULL,sent INTEGER NOT NULL DEFAULT 0)")
        .update();
  }

  /** GitHubとの連携で用いる重複の有無を返す。 */
  public Optional<Receipt> duplicate(String delivery, String digest) {
    var sameId =
        db.sql("SELECT * FROM github_webhook_deliveries WHERE id=?")
            .param(delivery)
            .query(
                (row, n) -> {
                  if (!row.getString("digest").equals(digest))
                    throw new OperationConflictException();
                  return new Receipt(
                      row.getString("id"), row.getString("status"), true, row.getInt("matched"));
                })
            .optional();
    return sameId.isPresent()
        ? sameId
        : db.sql("SELECT * FROM github_webhook_deliveries WHERE digest=?")
            .param(digest)
            .query(
                (row, n) ->
                    new Receipt(
                        row.getString("id"), row.getString("status"), true, row.getInt("matched")))
            .optional();
  }

  /** 実行順序を管理するため、通知を実行待ちに登録する。 */
  public void enqueueNotification(
      OwnedFact source, dev.mikoto2000.rei.attention.AttentionRepository.Item item) {
    db.sql(
            "INSERT OR IGNORE INTO github_notification_outbox(id,project,root,session,parent,kind,message,created) VALUES(?,?,?,?,?,?,?,?)")
        .params(
            item.id(),
            source.project().id(),
            source.project().root().toString(),
            source.sessionId(),
            source.id(),
            item.kind(),
            item.message(),
            item.createdAt().toString())
        .update();
  }

  /** GitHubとの連携で用いる待機中通知一覧を返す。 */
  public List<Notification> pendingNotifications() {
    return db.sql(
            "SELECT * FROM github_notification_outbox WHERE sent=0 ORDER BY julianday(created),id LIMIT 16")
        .query(
            (row, n) ->
                new Notification(
                    row.getString("id"),
                    row.getString("project"),
                    row.getString("root"),
                    row.getString("session"),
                    row.getString("parent"),
                    row.getString("kind"),
                    row.getString("message"),
                    Instant.parse(row.getString("created"))))
        .list();
  }

  /** GitHubとの連携に関する通知送信を処理する。 */
  public void notificationSent(String id) {
    db.sql("UPDATE github_notification_outbox SET sent=1 WHERE id=? AND sent=0").param(id).update();
  }

  /** GitHubとの連携に関する通知抑制されたを処理する。 */
  public void notificationSuppressed(String id) {
    db.sql("UPDATE github_notification_outbox SET sent=-1 WHERE id=? AND sent=0")
        .param(id)
        .update();
  }

  /** 要求を後続の処理へ引き渡すため、Git連携先事実保存と検索の窓口を受け付ける。 */
  public Receipt accept(
      String delivery,
      String digest,
      String event,
      List<OwnedFact> facts,
      int maxDeliveries,
      int maxFacts,
      java.util.function.Consumer<OwnedFact> effects) {
    return tx.execute(
        status -> {
          var existing =
              db.sql("SELECT * FROM github_webhook_deliveries WHERE id=?")
                  .param(delivery)
                  .query(
                      (row, n) -> {
                        if (row.getString("id").equals(delivery)
                            && !row.getString("digest").equals(digest))
                          throw new OperationConflictException();
                        return new Receipt(
                            row.getString("id"),
                            row.getString("status"),
                            true,
                            row.getInt("matched"));
                      })
                  .optional();
          if (existing.isPresent()) return existing.get();
          var replay =
              db.sql("SELECT * FROM github_webhook_deliveries WHERE digest=?")
                  .param(digest)
                  .query(
                      (row, n) ->
                          new Receipt(
                              row.getString("id"),
                              row.getString("status"),
                              true,
                              row.getInt("matched")))
                  .optional();
          if (replay.isPresent()) return replay.get();
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (facts.size() > 128
              || db.sql("SELECT COUNT(*) FROM github_webhook_facts").query(Long.class).single()
                      + facts.size()
                  > maxFacts) throw new CapacityExceeded();
          String state = facts.isEmpty() ? "IGNORED" : "APPLIED";
          int inserted =
              db.sql(
                      "INSERT INTO github_webhook_deliveries SELECT ?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM github_webhook_deliveries)<?")
                  .params(
                      delivery,
                      digest,
                      event,
                      state,
                      facts.size(),
                      clock.instant().toString(),
                      maxDeliveries)
                  .update();
          if (inserted != 1) throw new CapacityExceeded();
          for (var owned : facts) {
            var fact = owned.fact();
            db.sql("INSERT INTO github_webhook_facts VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                .params(
                    owned.id(),
                    delivery,
                    owned.project().id(),
                    owned.project().root().toString(),
                    owned.sessionId(),
                    fact.sourceId(),
                    fact.type(),
                    fact.repository(),
                    fact.pullRequest(),
                    encode(fact),
                    clock.instant().toString())
                .update();
            effects.accept(owned);
          }
          return new Receipt(delivery, state, false, facts.size());
        });
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public View get(ProjectContext owner, String session, String id) {
    return db.sql(
            "SELECT * FROM github_webhook_facts WHERE id=? AND project=? AND root=? AND session=?")
        .params(id, owner.id(), owner.root().toString(), session)
        .query(
            (row, n) ->
                new View(
                    row.getString("id"),
                    row.getString("delivery"),
                    owner.id(),
                    session,
                    row.getString("source"),
                    decode(row.getString("fact")),
                    Instant.parse(row.getString("received"))))
        .optional()
        .orElseThrow(() -> new ResourceNotFoundException("GitHub fact"));
  }

  /** GitHubとの連携で用いる対象配信を返す。 */
  public List<View> forDelivery(ProjectContext owner, String session, String delivery) {
    var views =
        db.sql(
                "SELECT * FROM github_webhook_facts WHERE delivery=? AND project=? AND root=? AND session=? ORDER BY id LIMIT 128")
            .params(delivery, owner.id(), owner.root().toString(), session)
            .query(
                (row, n) ->
                    new View(
                        row.getString("id"),
                        delivery,
                        owner.id(),
                        session,
                        row.getString("source"),
                        decode(row.getString("fact")),
                        Instant.parse(row.getString("received"))))
            .list();
    if (views.isEmpty()) throw new ResourceNotFoundException("GitHub facts");
    return views;
  }

  /** GitHubとの連携において、統合済みに該当するか判定する。 */
  public boolean merged(String project, String root, String session, String repository, int pr) {
    return db.sql(
                "SELECT COUNT(*) FROM github_webhook_facts WHERE project=? AND root=? AND session=? AND repository=? AND pr=? AND type='PR_MERGED'")
            .params(project, root, session, repository.toLowerCase(Locale.ROOT), pr)
            .query(Long.class)
            .single()
        > 0;
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(GitHubFact fact) {
    try {
      return json.writeValueAsString(fact);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("GitHub fact encoding failed", invalid);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private GitHubFact decode(String fact) {
    try {
      return json.readValue(fact, GitHubFact.class);
    } catch (java.io.IOException invalid) {
      throw new IllegalStateException("GitHub fact decoding failed", invalid);
    }
  }

  /** GitHubとの連携に必要な許容件数上限超過を扱う。 */
  public static final class CapacityExceeded extends RuntimeException {
    /** 既定の状態でCapacityExceededを初期化する。 */
    public CapacityExceeded() {
      super("GitHub receipt capacity reached");
    }
  }
}
