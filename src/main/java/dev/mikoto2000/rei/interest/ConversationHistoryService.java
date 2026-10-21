package dev.mikoto2000.rei.interest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 会話履歴のユースケースを実現する。 */
@Service
public class ConversationHistoryService {

  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 指定された値や利用先を使って、ConversationHistoryServiceを初期化する。 */
  public ConversationHistoryService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
  }

  /** 対象を特定できるよう、直近利用者メッセージ一覧を探す。 */
  public List<ConversationSnippet> findRecentUserMessages(int lookbackDays, int limit) {
    long cutoffEpochMillis =
        Instant.now().minusSeconds((long) lookbackDays * 24 * 60 * 60).toEpochMilli();
    List<ConversationSnippet> rows =
        jdbcClient
            .sql(
                """
        SELECT conversation_id, content, timestamp
        FROM SPRING_AI_CHAT_MEMORY
        WHERE type = 'USER' AND timestamp >= ?
        ORDER BY timestamp DESC
        LIMIT ?
        """)
            .params(cutoffEpochMillis, limit)
            .query(
                (rs, rowNum) ->
                    new ConversationSnippet(
                        rs.getString("conversation_id"),
                        rs.getString("content"),
                        OffsetDateTime.ofInstant(
                            Instant.ofEpochMilli(rs.getLong("timestamp")), ZoneOffset.UTC)))
            .list();
    Collections.reverse(rows);
    return rows;
  }
}
