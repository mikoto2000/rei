package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConversationHistorySearchServiceTest {

  @TempDir
  java.nio.file.Path tempDir;

  private DriverManagerDataSource dataSource;
  private ConversationHistorySearchService service;

  @BeforeEach
  void setUp() throws Exception {
    dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("conversation-search.db"));
    initializeSchema();
    service = new ConversationHistorySearchService(dataSource);
  }

  @Test
  void searchesChatAndBlueskyReplyHistories() throws Exception {
    long now = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli();
    insertChat("c1", "USER", "gantt.csv を再スケジュールしたい", now);
    insertChat("c1", "ASSISTANT", "確認します", now + 1_000);
    insertBluesky("alice.bsky.social", "user", "gantt.csv の話をしました", "2026-08-14T00:00:02Z");

    var results = service.search("gantt.csv", "all", null, null, null, 10);

    assertThat(results).extracting(ConversationSearchResult::conversationId)
        .containsExactly("bluesky-reply:alice.bsky.social", "chat:c1");
    assertThat(results.get(0).scope()).isEqualTo("bluesky-reply");
    assertThat(results.get(1).speaker()).isEqualTo("user");
  }

  @Test
  void filtersByScopeSpeakerAndDate() throws Exception {
    insertChat("c1", "USER", "古い grep の話", Instant.parse("2026-08-01T00:00:00Z").toEpochMilli());
    insertChat("c2", "ASSISTANT", "grep の説明", Instant.parse("2026-08-14T00:00:00Z").toEpochMilli());
    insertChat("c3", "USER", "grep ツールを追加したい", Instant.parse("2026-08-14T01:00:00Z").toEpochMilli());
    insertBluesky("alice.bsky.social", "user", "grep の投稿", "2026-08-14T02:00:00Z");

    var results = service.search("grep", "chat", "user", "2026-08-14", "2026-08-14", 10);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).conversationId()).isEqualTo("chat:c3");
    assertThat(results.get(0).content()).isEqualTo("grep ツールを追加したい");
  }

  @Test
  void returnsChatDetailInChronologicalOrder() throws Exception {
    long now = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli();
    insertChat("c1", "USER", "質問", now);
    insertChat("c1", "ASSISTANT", "回答", now + 1_000);

    ConversationHistoryDetail detail = service.detail("chat:c1", 10);

    assertThat(detail.scope()).isEqualTo("chat");
    assertThat(detail.messages()).extracting(ConversationHistoryMessage::content)
        .containsExactly("質問", "回答");
  }

  @Test
  void returnsBlueskyReplyDetailInChronologicalOrder() throws Exception {
    insertBluesky("alice.bsky.social", "user", "元投稿", "2026-08-14T00:00:00Z");
    insertBluesky("alice.bsky.social", "assistant", "返信", "2026-08-14T00:00:01Z");

    ConversationHistoryDetail detail = service.detail("bluesky-reply:alice.bsky.social", 10);

    assertThat(detail.scope()).isEqualTo("bluesky-reply");
    assertThat(detail.messages()).extracting(ConversationHistoryMessage::content)
        .containsExactly("元投稿", "返信");
  }

  @Test
  void classifiesNewScopes() throws Exception {
    long now = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli();
    insertChat("chat:main", "USER", "通常チャット", now);
    insertChat("tool:search", "USER", "tool チャット", now + 1_000);
    insertChat("bluesky-manual:at://did:plc:xxx/app.bsky.feed.post/abc", "USER", "手動返信チャット", now + 2_000);

    var results = service.search("チャット", "all", null, null, null, 10);

    assertThat(results).extracting(ConversationSearchResult::scope)
        .contains("chat", "tool", "bluesky-manual");
  }

  @Test
  void filtersByNewScopes() throws Exception {
    long now = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli();
    insertChat("chat:main", "USER", "通常チャットの話", now);
    insertChat("tool:search", "USER", "tool の話", now + 1_000);
    insertChat("bluesky-manual:at://did:plc:xxx/app.bsky.feed.post/abc", "USER", "手動返信の話", now + 2_000);

    var toolResults = service.search("話", "tool", null, null, null, 10);
    assertThat(toolResults).hasSize(1);
    assertThat(toolResults.get(0).conversationId()).isEqualTo("tool:search");
    assertThat(toolResults.get(0).scope()).isEqualTo("tool");

    var manualResults = service.search("話", "bluesky-manual", null, null, null, 10);
    assertThat(manualResults).hasSize(1);
    assertThat(manualResults.get(0).conversationId())
        .isEqualTo("bluesky-manual:at://did:plc:xxx/app.bsky.feed.post/abc");
    assertThat(manualResults.get(0).scope()).isEqualTo("bluesky-manual");
  }

  @Test
  void returnsDetailForNewScopes() throws Exception {
    long now = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli();
    insertChat("tool:search", "USER", "tool 質問", now);
    insertChat("tool:search", "ASSISTANT", "tool 回答", now + 1_000);

    ConversationHistoryDetail toolDetail = service.detail("tool:search", 10);
    assertThat(toolDetail.scope()).isEqualTo("tool");
    assertThat(toolDetail.messages()).extracting(ConversationHistoryMessage::content)
        .containsExactly("tool 質問", "tool 回答");

    ConversationHistoryDetail manualDetail = service.detail("bluesky-manual:abc", 10);
    assertThat(manualDetail.scope()).isEqualTo("bluesky-manual");
  }

  @Test
  void keepsLegacyUnprefixedChatHistoriesAsChatScope() throws Exception {
    long now = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli();
    insertChat("legacy", "USER", "古い通常チャット", now);

    var results = service.search("古い", "chat", null, null, null, 10);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).conversationId()).isEqualTo("chat:legacy");
  }

  @Test
  void rejectsBlankQuery() {
    assertThatThrownBy(() -> service.search(" ", "all", null, null, null, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("query");
  }

  private void initializeSchema() throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE SPRING_AI_CHAT_MEMORY (
            conversation_id TEXT NOT NULL,
            content TEXT NOT NULL,
            type TEXT NOT NULL,
            timestamp INTEGER NOT NULL
          )
          """);
      statement.executeUpdate("""
          CREATE TABLE bluesky_reply_conversations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            handle TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL
          )
          """);
    }
  }

  private void insertChat(String conversationId, String type, String content, long timestamp) throws Exception {
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          INSERT INTO SPRING_AI_CHAT_MEMORY(conversation_id, content, type, timestamp)
          VALUES ('%s', '%s', '%s', %d)
          """.formatted(conversationId, content, type, timestamp));
    }
  }

  private void insertBluesky(String handle, String role, String content, String createdAt) throws Exception {
    OffsetDateTime.parse(createdAt).withOffsetSameInstant(ZoneOffset.UTC);
    try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          INSERT INTO bluesky_reply_conversations(handle, role, content, created_at)
          VALUES ('%s', '%s', '%s', '%s')
          """.formatted(handle, role, content, createdAt));
    }
  }
}
