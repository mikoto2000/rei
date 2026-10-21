package dev.mikoto2000.rei.bluesky;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.llm.ConversationIds;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bluesky返信会話を保存・検索するための窓口。 */
@Repository
public class BlueskyReplyConversationRepository {

  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 会話ログ保存先。 */
  private final ConversationLogStore conversationLogStore;

  /** Blueskyとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public BlueskyReplyConversationRepository(DataSource dataSource) {
    this(dataSource, null);
  }

  /** 指定された値や利用先を使って、BlueskyReplyConversationRepositoryを初期化する。 */
  @Autowired
  public BlueskyReplyConversationRepository(
      DataSource dataSource, ConversationLogStore conversationLogStore) {
    this.jdbcClient = JdbcClient.create(dataSource);
    this.conversationLogStore = conversationLogStore;
    initializeSchema();
  }

  /** 既存の記録へ経緯を残すため、利用者メッセージを追記する。 */
  public void appendUserMessage(String handle, String content) {
    append(handle, "user", content);
  }

  /** 既存の記録へ経緯を残すため、アシスタントメッセージを追記する。 */
  public void appendAssistantMessage(String handle, String content) {
    append(handle, "assistant", content);
  }

  /** 対象を特定できるよう、直近を探す。 */
  public List<ConversationMessage> findRecent(String handle, int limit) {
    int safeLimit = Math.max(1, limit);
    return jdbcClient
        .sql(
            """
        SELECT role, content, created_at
        FROM bluesky_reply_conversations
        WHERE handle = ?
        ORDER BY id DESC
        LIMIT ?
        """)
        .params(handle, safeLimit)
        .query(
            (rs, rowNum) ->
                new ConversationMessage(
                    rs.getString("role"),
                    rs.getString("content"),
                    OffsetDateTime.parse(rs.getString("created_at"))))
        .list()
        .reversed();
  }

  /** 利用可能な対象を確認できるよう、操作対象一覧を一覧にする。 */
  public List<String> listHandles() {
    return jdbcClient
        .sql(
            """
        SELECT DISTINCT handle
        FROM bluesky_reply_conversations
        ORDER BY handle
        """)
        .query(String.class)
        .list();
  }

  /** 既存の記録へ経緯を残すため、Bluesky返信会話保存と検索の窓口を追記する。 */
  private void append(String handle, String role, String content) {
    jdbcClient
        .sql(
            """
        INSERT INTO bluesky_reply_conversations(handle, role, content, created_at)
        VALUES (?, ?, ?, ?)
        """)
        .params(handle, role, content, OffsetDateTime.now().toString())
        .update();
    if (conversationLogStore != null) {
      conversationLogStore.append(ConversationIds.blueskyReply(handle), role, content);
    }
  }

  /** Blueskyとの連携に関する初期化データ形式を処理する。 */
  private void initializeSchema() {
    jdbcClient
        .sql(
            """
        CREATE TABLE IF NOT EXISTS bluesky_reply_conversations (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          handle TEXT NOT NULL,
          role TEXT NOT NULL,
          content TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """)
        .update();
  }

  /**
   * Blueskyとの連携で受け渡す会話メッセージを表す。
   *
   * @param role 役割
   * @param content 内容
   * @param createdAt 作成日時
   */
  public record ConversationMessage(String role, String content, OffsetDateTime createdAt) {}
}
