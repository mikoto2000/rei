/*
 * Copyright 2023-present the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package dev.mikoto2000.rei.chatmemory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * SQLite message storage retaining Rei's existing table, sequence and timestamp contract. Adapted
 * from Spring AI 2.1.0 JdbcChatMemoryRepository; unlike Session storage this does not introduce
 * event tables or change eviction and tool-message persistence.
 */
public final class JdbcConversationMemoryStore implements ConversationMemoryStore {
  /** Existing metadata key, deliberately independent of this implementation's class name. */
  public static final String CONVERSATION_TS = "JdbcChatMemoryRepository_message_timestamp";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transaction;

  /** Connects to the existing data source; schema initialization remains explicit. */
  public JdbcConversationMemoryStore(DataSource dataSource) {
    Assert.notNull(dataSource, "dataSource cannot be null");
    jdbc = new JdbcTemplate(dataSource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  private JdbcConversationMemoryStore(JdbcTemplate jdbcTemplate) {
    Assert.notNull(jdbcTemplate, "jdbcTemplate cannot be null");
    DataSource dataSource = jdbcTemplate.getDataSource();
    Assert.notNull(dataSource, "jdbcTemplate dataSource cannot be null");
    jdbc = jdbcTemplate;
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  /** Uses the application's configured template, including query timeout and fetch settings. */
  public static JdbcConversationMemoryStore fromJdbcTemplate(JdbcTemplate jdbcTemplate) {
    return new JdbcConversationMemoryStore(jdbcTemplate);
  }

  @Override
  public List<String> findConversationIds() {
    return jdbc.query(
        "SELECT DISTINCT conversation_id FROM SPRING_AI_CHAT_MEMORY",
        (rs, row) -> Objects.requireNonNull(rs.getString(1)));
  }

  @Override
  public List<Message> findByConversationId(String conversationId) {
    Assert.hasText(conversationId, "conversationId cannot be null or empty");
    return jdbc
        .<Message>query(
            "SELECT content, type, timestamp FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY sequence_id",
            (rs, row) -> {
              String content = rs.getString(1);
              Timestamp timestamp = rs.getTimestamp(3);
              Map<String, Object> metadata =
                  timestamp == null ? Map.of() : Map.of(CONVERSATION_TS, timestamp.toInstant());
              return switch (MessageType.valueOf(rs.getString(2))) {
                case USER -> UserMessage.builder().text(content).metadata(metadata).build();
                case ASSISTANT ->
                    AssistantMessage.builder().content(content).properties(metadata).build();
                case SYSTEM -> SystemMessage.builder().text(content).metadata(metadata).build();
                case TOOL -> null;
              };
            },
            conversationId)
        .stream()
        .filter(Objects::nonNull)
        .toList();
  }

  @Override
  public void saveAll(String conversationId, List<Message> messages) {
    Assert.hasText(conversationId, "conversationId cannot be null or empty");
    Assert.notNull(messages, "messages cannot be null");
    Assert.noNullElements(messages, "messages cannot contain null elements");
    List<Message> persistable =
        messages.stream()
            .filter(m ->
                !(m instanceof ToolResponseMessage)
                    && !(m instanceof AssistantMessage a && a.hasToolCalls()))
            .toList();
    if (persistable.size() != messages.size()) {
      org.apache.commons.logging.LogFactory.getLog(JdbcConversationMemoryStore.class)
          .warn(
              "JdbcChatMemoryRepository does not support tool call messages. Some messages were filtered out for conversation: "
                  + conversationId);
    }
    transaction.executeWithoutResult(status -> {
      deleteByConversationId(conversationId);
      jdbc.batchUpdate(
          "INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp, sequence_id) VALUES (?, ?, ?, ?, ?)",
          new MessageBatch(conversationId, persistable));
    });
  }

  @Override
  public void deleteByConversationId(String conversationId) {
    Assert.hasText(conversationId, "conversationId cannot be null or empty");
    jdbc.update("DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?", conversationId);
  }

  /** Existing SQLite batch ordering and creation-time preservation. */
  private record MessageBatch(String conversationId, List<Message> messages)
      implements BatchPreparedStatementSetter {
    @Override
    public void setValues(PreparedStatement ps, int index) throws SQLException {
      Message message = messages.get(index);
      ps.setString(1, conversationId);
      ps.setString(2, message.getText());
      ps.setString(3, message.getMessageType().name());
      Object created = message.getMetadata().get(CONVERSATION_TS);
      ps.setTimestamp(
          4, Timestamp.from(created instanceof Instant instant ? instant : Instant.now()));
      ps.setLong(5, index);
    }

    @Override
    public int getBatchSize() {
      return messages.size();
    }
  }
}
