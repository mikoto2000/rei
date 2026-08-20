package dev.mikoto2000.rei.conversation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class ConversationHistorySearchService {

  static final int DEFAULT_SEARCH_LIMIT = 10;
  static final int MAX_SEARCH_LIMIT = 50;
  static final int DEFAULT_DETAIL_LIMIT = 50;
  static final int MAX_DETAIL_LIMIT = 100;
  private static final int SUMMARY_MAX_LENGTH = 120;
  private static final int CONTENT_MAX_LENGTH = 500;

  private final JdbcClient jdbcClient;

  public ConversationHistorySearchService(DataSource dataSource) {
    this.jdbcClient = JdbcClient.create(dataSource);
  }

  public List<ConversationSearchResult> search(String query, String scope, String speaker, String since, String until,
      Integer limit) {
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    String normalizedScope = normalizeScope(scope);
    String normalizedSpeaker = normalizeSpeaker(speaker);
    TimeRange timeRange = parseTimeRange(since, until);
    int safeLimit = normalizeLimit(limit, DEFAULT_SEARCH_LIMIT, MAX_SEARCH_LIMIT);
    List<ConversationSearchResult> results = new ArrayList<>();
    if (normalizedScope.equals("all") || normalizedScope.equals("chat")) {
      results.addAll(searchChat(query, normalizedSpeaker, timeRange, safeLimit));
    }
    if (normalizedScope.equals("all") || normalizedScope.equals("bluesky-reply")) {
      results.addAll(searchBlueskyReply(query, normalizedSpeaker, timeRange, safeLimit));
    }
    if (normalizedScope.equals("all") || normalizedScope.equals("bluesky-manual")) {
      results.addAll(searchBlueskyManual(query, normalizedSpeaker, timeRange, safeLimit));
    }
    if (normalizedScope.equals("all") || normalizedScope.equals("tool")) {
      results.addAll(searchTool(query, normalizedSpeaker, timeRange, safeLimit));
    }
    results.sort((a, b) -> b.timestamp().compareTo(a.timestamp()));
    return results.stream().limit(safeLimit).toList();
  }

  public ConversationHistoryDetail detail(String conversationId, Integer limit) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId must not be blank");
    }
    int safeLimit = normalizeLimit(limit, DEFAULT_DETAIL_LIMIT, MAX_DETAIL_LIMIT);
    if (conversationId.startsWith("chat:")) {
      return new ConversationHistoryDetail(conversationId, "chat", findChatDetailWithFallback(conversationId, "chat:".length(), safeLimit));
    }
    if (conversationId.startsWith("bluesky-reply:")) {
      String handle = conversationId.substring("bluesky-reply:".length());
      return new ConversationHistoryDetail(conversationId, "bluesky-reply", findBlueskyReplyDetail(handle, safeLimit));
    }
    if (conversationId.startsWith("bluesky-manual:")) {
      return new ConversationHistoryDetail(conversationId, "bluesky-manual", findChatDetailWithFallback(conversationId, "bluesky-manual:".length(), safeLimit));
    }
    if (conversationId.startsWith("tool:")) {
      return new ConversationHistoryDetail(conversationId, "tool", findChatDetailWithFallback(conversationId, "tool:".length(), safeLimit));
    }
    throw new IllegalArgumentException("conversationId must start with chat:, bluesky-reply:, bluesky-manual:, or tool:");
  }

  private List<ConversationSearchResult> searchChat(String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql = new StringBuilder("""
        SELECT conversation_id, content, type, timestamp
        FROM SPRING_AI_CHAT_MEMORY
        WHERE 1 = 1
        AND conversation_id NOT LIKE 'bluesky-manual:%'
        AND conversation_id NOT LIKE 'tool:%'
        """);
    List<Object> params = new ArrayList<>();
    addQueryConditions(sql, params, query);
    if (speaker != null) {
      sql.append(" AND lower(type) = ?\n");
      params.add(chatSpeaker(speaker));
    }
    if (timeRange.sinceEpochMillis() != null) {
      sql.append(" AND timestamp >= ?\n");
      params.add(timeRange.sinceEpochMillis());
    }
    if (timeRange.untilEpochMillis() != null) {
      sql.append(" AND timestamp <= ?\n");
      params.add(timeRange.untilEpochMillis());
    }
    sql.append(" ORDER BY timestamp DESC LIMIT ?");
    params.add(limit);
    try {
      return jdbcClient.sql(sql.toString())
          .params(params)
          .query((rs, rowNum) -> {
            String content = rs.getString("content");
            String timestamp = Instant.ofEpochMilli(rs.getLong("timestamp")).toString();
            String conversationId = rs.getString("conversation_id");
            return new ConversationSearchResult(
                conversationId.startsWith("chat:") ? conversationId : "chat:" + conversationId,
                "chat",
                normalizeChatSpeaker(rs.getString("type")),
                timestamp,
                preview(content, SUMMARY_MAX_LENGTH),
                preview(content, CONTENT_MAX_LENGTH));
          })
          .list();
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private List<ConversationSearchResult> searchBlueskyManual(String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql = new StringBuilder("""
        SELECT conversation_id, content, type, timestamp
        FROM SPRING_AI_CHAT_MEMORY
        WHERE 1 = 1
        AND conversation_id LIKE 'bluesky-manual:%'
        """);
    List<Object> params = new ArrayList<>();
    addQueryConditions(sql, params, query);
    if (speaker != null) {
      sql.append(" AND lower(type) = ?\n");
      params.add(chatSpeaker(speaker));
    }
    if (timeRange.sinceEpochMillis() != null) {
      sql.append(" AND timestamp >= ?\n");
      params.add(timeRange.sinceEpochMillis());
    }
    if (timeRange.untilEpochMillis() != null) {
      sql.append(" AND timestamp <= ?\n");
      params.add(timeRange.untilEpochMillis());
    }
    sql.append(" ORDER BY timestamp DESC LIMIT ?");
    params.add(limit);
    try {
      return jdbcClient.sql(sql.toString())
          .params(params)
          .query((rs, rowNum) -> {
            String content = rs.getString("content");
            String timestamp = Instant.ofEpochMilli(rs.getLong("timestamp")).toString();
            String conversationId = rs.getString("conversation_id");
            return new ConversationSearchResult(
                conversationId,
                "bluesky-manual",
                normalizeChatSpeaker(rs.getString("type")),
                timestamp,
                preview(content, SUMMARY_MAX_LENGTH),
                preview(content, CONTENT_MAX_LENGTH));
          })
          .list();
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private List<ConversationSearchResult> searchTool(String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql = new StringBuilder("""
        SELECT conversation_id, content, type, timestamp
        FROM SPRING_AI_CHAT_MEMORY
        WHERE 1 = 1
        AND conversation_id LIKE 'tool:%'
        """);
    List<Object> params = new ArrayList<>();
    addQueryConditions(sql, params, query);
    if (speaker != null) {
      sql.append(" AND lower(type) = ?\n");
      params.add(chatSpeaker(speaker));
    }
    if (timeRange.sinceEpochMillis() != null) {
      sql.append(" AND timestamp >= ?\n");
      params.add(timeRange.sinceEpochMillis());
    }
    if (timeRange.untilEpochMillis() != null) {
      sql.append(" AND timestamp <= ?\n");
      params.add(timeRange.untilEpochMillis());
    }
    sql.append(" ORDER BY timestamp DESC LIMIT ?");
    params.add(limit);
    try {
      return jdbcClient.sql(sql.toString())
          .params(params)
          .query((rs, rowNum) -> {
            String content = rs.getString("content");
            String timestamp = Instant.ofEpochMilli(rs.getLong("timestamp")).toString();
            String conversationId = rs.getString("conversation_id");
            return new ConversationSearchResult(
                conversationId,
                "tool",
                normalizeChatSpeaker(rs.getString("type")),
                timestamp,
                preview(content, SUMMARY_MAX_LENGTH),
                preview(content, CONTENT_MAX_LENGTH));
          })
          .list();
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private List<ConversationSearchResult> searchBlueskyReply(String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql = new StringBuilder("""
        SELECT handle, role, content, created_at
        FROM bluesky_reply_conversations
        WHERE 1 = 1
        """);
    List<Object> params = new ArrayList<>();
    addQueryConditions(sql, params, query);
    if (speaker != null) {
      sql.append(" AND lower(role) = ?\n");
      params.add(speaker);
    }
    if (timeRange.sinceText() != null) {
      sql.append(" AND created_at >= ?\n");
      params.add(timeRange.sinceText());
    }
    if (timeRange.untilText() != null) {
      sql.append(" AND created_at <= ?\n");
      params.add(timeRange.untilText());
    }
    sql.append(" ORDER BY created_at DESC LIMIT ?");
    params.add(limit);
    try {
      return jdbcClient.sql(sql.toString())
          .params(params)
          .query((rs, rowNum) -> {
            String content = rs.getString("content");
            return new ConversationSearchResult(
                "bluesky-reply:" + rs.getString("handle"),
                "bluesky-reply",
                rs.getString("role"),
                normalizeTimestamp(rs.getString("created_at")),
                preview(content, SUMMARY_MAX_LENGTH),
                preview(content, CONTENT_MAX_LENGTH));
          })
          .list();
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private List<ConversationHistoryMessage> findChatDetail(String conversationId, int limit) {
    try {
      List<ConversationHistoryMessage> messages = jdbcClient.sql("""
          SELECT type, content, timestamp
          FROM SPRING_AI_CHAT_MEMORY
          WHERE conversation_id = ?
          ORDER BY timestamp DESC
          LIMIT ?
          """)
          .params(conversationId, limit)
          .query((rs, rowNum) -> new ConversationHistoryMessage(
              normalizeChatSpeaker(rs.getString("type")),
              Instant.ofEpochMilli(rs.getLong("timestamp")).toString(),
              rs.getString("content")))
          .list();
      Collections.reverse(messages);
      return messages;
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private List<ConversationHistoryMessage> findChatDetailWithFallback(String conversationId, int prefixLength,
      int limit) {
    List<ConversationHistoryMessage> messages = findChatDetail(conversationId, limit);
    if (!messages.isEmpty()) {
      return messages;
    }
    return findChatDetail(conversationId.substring(prefixLength), limit);
  }

  private List<ConversationHistoryMessage> findBlueskyReplyDetail(String handle, int limit) {
    try {
      List<ConversationHistoryMessage> messages = jdbcClient.sql("""
          SELECT role, content, created_at
          FROM bluesky_reply_conversations
          WHERE handle = ?
          ORDER BY id DESC
          LIMIT ?
          """)
          .params(handle, limit)
          .query((rs, rowNum) -> new ConversationHistoryMessage(
              rs.getString("role"),
              normalizeTimestamp(rs.getString("created_at")),
              rs.getString("content")))
          .list();
      Collections.reverse(messages);
      return messages;
    } catch (DataAccessException e) {
      return List.of();
    }
  }

  private void addQueryConditions(StringBuilder sql, List<Object> params, String query) {
    for (String term : query.strip().split("\\s+")) {
      if (term.isBlank()) {
        continue;
      }
      sql.append(" AND lower(content) LIKE ?\n");
      params.add("%" + term.toLowerCase(Locale.ROOT) + "%");
    }
  }

  private String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "all";
    }
    String normalized = scope.toLowerCase(Locale.ROOT);
    if (normalized.equals("all") || normalized.equals("chat") || normalized.equals("bluesky-reply")
        || normalized.equals("bluesky-manual") || normalized.equals("tool")) {
      return normalized;
    }
    throw new IllegalArgumentException("scope must be all, chat, bluesky-reply, bluesky-manual, or tool");
  }

  private String normalizeSpeaker(String speaker) {
    if (speaker == null || speaker.isBlank()) {
      return null;
    }
    return speaker.toLowerCase(Locale.ROOT);
  }

  private String chatSpeaker(String speaker) {
    if (speaker.equals("assistant") || speaker.equals("user") || speaker.equals("system") || speaker.equals("tool")) {
      return speaker;
    }
    return speaker;
  }

  private String normalizeChatSpeaker(String type) {
    return type == null ? "" : type.toLowerCase(Locale.ROOT);
  }

  private int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
    if (limit == null || limit <= 0) {
      return defaultLimit;
    }
    return Math.min(limit, maxLimit);
  }

  private TimeRange parseTimeRange(String since, String until) {
    OffsetDateTime sinceTime = parseDateTime(since, false);
    OffsetDateTime untilTime = parseDateTime(until, true);
    return new TimeRange(
        sinceTime == null ? null : sinceTime.toInstant().toEpochMilli(),
        untilTime == null ? null : untilTime.toInstant().toEpochMilli(),
        sinceTime == null ? null : sinceTime.toString(),
        untilTime == null ? null : untilTime.toString());
  }

  private OffsetDateTime parseDateTime(String value, boolean endOfDay) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException ignored) {
      LocalDate date = LocalDate.parse(value);
      return endOfDay
          ? date.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).minusNanos(1)
          : date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
  }

  private String preview(String content, int maxLength) {
    if (content == null) {
      return "";
    }
    String normalized = content.replaceAll("\\s+", " ").strip();
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength) + "...";
  }

  private String normalizeTimestamp(String timestamp) {
    if (timestamp == null || timestamp.isBlank()) {
      return "";
    }
    try {
      return OffsetDateTime.parse(timestamp).toInstant().toString();
    } catch (DateTimeParseException e) {
      return timestamp;
    }
  }

  private record TimeRange(Long sinceEpochMillis, Long untilEpochMillis, String sinceText, String untilText) {
  }
}
