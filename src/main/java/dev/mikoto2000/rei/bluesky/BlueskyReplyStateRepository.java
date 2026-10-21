package dev.mikoto2000.rei.bluesky;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bluesky返信状態を保存・検索するための窓口。 */
@Repository
public class BlueskyReplyStateRepository {

  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 指定された値や利用先を使って、BlueskyReplyStateRepositoryを初期化する。 */
  public BlueskyReplyStateRepository(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
    initializeSchema();
  }

  /** 対象を特定できるよう、最後に確認した日時を探す。 */
  public Optional<UserState> findLastSeen(String handle) {
    return jdbcClient
        .sql(
            "SELECT handle, last_seen_post_uri, last_seen_indexed_at, updated_at FROM bluesky_reply_user_state WHERE handle = ?")
        .param(handle)
        .query(this::mapUserState)
        .optional();
  }

  /** 後から参照できるよう、最後に確認した日時を保存する。 */
  public void saveLastSeen(String handle, String postUri, OffsetDateTime indexedAt) {
    String now = OffsetDateTime.now().toString();
    int updated =
        jdbcClient
            .sql(
                "UPDATE bluesky_reply_user_state SET last_seen_post_uri = ?, last_seen_indexed_at = ?, updated_at = ? WHERE handle = ?")
            .params(postUri, indexedAt == null ? null : indexedAt.toString(), now, handle)
            .update();
    if (updated == 0) {
      jdbcClient
          .sql(
              "INSERT INTO bluesky_reply_user_state(handle, last_seen_post_uri, last_seen_indexed_at, updated_at) VALUES (?, ?, ?, ?)")
          .params(handle, postUri, indexedAt == null ? null : indexedAt.toString(), now)
          .update();
    }
  }

  /** Blueskyとの連携において、既にRepliedかどうかを判定する。 */
  public boolean isAlreadyReplied(String postUri) {
    Integer count =
        jdbcClient
            .sql("SELECT COUNT(*) FROM bluesky_replied_posts WHERE post_uri = ?")
            .param(postUri)
            .query(Integer.class)
            .single();
    return count != null && count > 0;
  }

  /** 後続の判断に現在の状態を反映するため、Repliedの状態を記録する。 */
  public void markReplied(String postUri, String handle, String repliedPostUri) {
    jdbcClient
        .sql(
            "INSERT OR IGNORE INTO bluesky_replied_posts(post_uri, handle, replied_post_uri, replied_at) VALUES (?, ?, ?, ?)")
        .params(postUri, handle, repliedPostUri, OffsetDateTime.now().toString())
        .update();
  }

  /**
   * Blueskyとの連携で判断に用いる試行利用権の確保返信の条件を確認する。
   *
   * <p>Atomically reserves a target across manual replies, scheduled runs and processes sharing
   * this DB.
   */
  public boolean tryClaimReply(String postUri) {
    return jdbcClient
            .sql(
                """
        INSERT OR IGNORE INTO bluesky_reply_claims(post_uri, claimed_at)
        SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM bluesky_replied_posts WHERE post_uri = ?)
        """)
            .params(postUri, OffsetDateTime.now().toString(), postUri)
            .update()
        == 1;
  }

  /**
   * 後続の処理が利用できるよう、返信利用権の確保を解放する。
   *
   * <p>Release only when the API explicitly reports failure; an exception may follow a successful
   * send.
   */
  public void releaseReplyClaim(String postUri) {
    jdbcClient.sql("DELETE FROM bluesky_reply_claims WHERE post_uri = ?").param(postUri).update();
  }

  /** 対象の規模を判断するため、今日を数える。 */
  public int countToday(String handle, LocalDate date) {
    Integer count =
        jdbcClient
            .sql("SELECT count FROM bluesky_reply_daily_count WHERE handle = ? AND date = ?")
            .params(handle, date.toString())
            .query(Integer.class)
            .optional()
            .orElse(0);
    return count == null ? 0 : count;
  }

  /** Blueskyとの連携に関するincrement今日を処理する。 */
  public void incrementToday(String handle, LocalDate date) {
    int updated =
        jdbcClient
            .sql(
                "UPDATE bluesky_reply_daily_count SET count = count + 1 WHERE handle = ? AND date = ?")
            .params(handle, date.toString())
            .update();
    if (updated == 0) {
      jdbcClient
          .sql("INSERT INTO bluesky_reply_daily_count(handle, date, count) VALUES (?, ?, 1)")
          .params(handle, date.toString())
          .update();
    }
  }

  /** Blueskyとの連携に関する初期化データ形式を処理する。 */
  private void initializeSchema() {
    jdbcClient
        .sql(
            """
        CREATE TABLE IF NOT EXISTS bluesky_reply_claims (
          post_uri TEXT PRIMARY KEY,
          claimed_at TEXT NOT NULL
        )
        """)
        .update();
    jdbcClient
        .sql(
            """
        CREATE TABLE IF NOT EXISTS bluesky_reply_user_state (
          handle TEXT PRIMARY KEY,
          last_seen_post_uri TEXT,
          last_seen_indexed_at TEXT,
          updated_at TEXT NOT NULL
        )
        """)
        .update();
    jdbcClient
        .sql(
            """
        CREATE TABLE IF NOT EXISTS bluesky_replied_posts (
          post_uri TEXT PRIMARY KEY,
          handle TEXT NOT NULL,
          replied_post_uri TEXT,
          replied_at TEXT NOT NULL
        )
        """)
        .update();
    jdbcClient
        .sql(
            """
        CREATE TABLE IF NOT EXISTS bluesky_reply_daily_count (
          handle TEXT NOT NULL,
          date TEXT NOT NULL,
          count INTEGER NOT NULL,
          PRIMARY KEY(handle, date)
        )
        """)
        .update();
  }

  /** Blueskyとの連携で用いる対応表利用者状態を返す。 */
  private UserState mapUserState(ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new UserState(
        rs.getString("handle"),
        rs.getString("last_seen_post_uri"),
        fromString(rs.getString("last_seen_indexed_at")),
        fromString(rs.getString("updated_at")));
  }

  /** Blueskyとの連携で用いる起点文字列表現を返す。 */
  private OffsetDateTime fromString(String value) {
    return value == null ? null : OffsetDateTime.parse(value);
  }

  /**
   * Blueskyとの連携で受け渡す利用者状態を表す。
   *
   * @param handle 操作対象
   * @param lastSeenPostUri 最後に確認した日時投稿URI
   * @param lastSeenIndexedAt 最後に確認した日時Indexed日時
   * @param updatedAt 更新日時
   */
  public record UserState(
      String handle,
      String lastSeenPostUri,
      OffsetDateTime lastSeenIndexedAt,
      OffsetDateTime updatedAt) {}
}
