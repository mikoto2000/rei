package dev.mikoto2000.rei.conversation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 会話履歴検索のユースケースを実現する。 */
@Service
public class ConversationHistorySearchService {

  /** 既定検索上限。 */
  static final int DEFAULT_SEARCH_LIMIT = 10;

  /** 最大検索上限。 */
  static final int MAX_SEARCH_LIMIT = 50;

  /** 既定詳細上限。 */
  static final int DEFAULT_DETAIL_LIMIT = 50;

  /** 最大詳細上限。 */
  static final int MAX_DETAIL_LIMIT = 100;

  /** 要約最大長さ。 */
  private static final int SUMMARY_MAX_LENGTH = 120;

  /** 内容最大長さ。 */
  private static final int CONTENT_MAX_LENGTH = 500;

  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 会話ログ保存先。 */
  private final ConversationLogStore conversationLogStore;

  /** プロジェクト関連情報の取得。 */
  private final ProjectHistoryRetrieval projectRetrieval;

  /** イベント一覧。 */
  private dev.mikoto2000.rei.event.AgentEventFactory events;

  /** 通知先。 */
  private dev.mikoto2000.rei.event.AgentEventPublisher publisher;

  /** 会話履歴と受付記録の管理で使用する履歴イベント一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setHistoryEvents(
      dev.mikoto2000.rei.event.AgentEventFactory events,
      dev.mikoto2000.rei.event.AgentEventPublisher publisher) {
    this.events = events;
    this.publisher = publisher;
  }

  /** 指定された値や利用先を使って、ConversationHistorySearchServiceを初期化する。 */
  public ConversationHistorySearchService(
      DataSource dataSource, ConversationLogStore conversationLogStore) {
    this.jdbcClient = JdbcClient.create(dataSource);
    this.conversationLogStore = conversationLogStore;
    this.projectRetrieval =
        new ProjectHistoryRetrieval(
            conversationLogStore,
            new dev.mikoto2000.rei.core.project.ProjectRegistry(
                dev.mikoto2000.rei.core.datasource.ReiPaths.projectsFilePath()));
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<ConversationSearchResult> search(
      String query, String scope, String speaker, String since, String until, Integer limit) {
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    if (project != null)
      return search(
          new HistorySearchRequest(
              query,
              project.id(),
              null,
              HistorySearchScope.CURRENT_PROJECT_PREFERRED,
              scope,
              speaker,
              since,
              until,
              limit));
    if (query == null || query.isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    String normalizedScope = normalizeScope(scope);
    String normalizedSpeaker = normalizeSpeaker(speaker);
    TimeRange timeRange = parseTimeRange(since, until);
    int safeLimit = normalizeLimit(limit, DEFAULT_SEARCH_LIMIT, MAX_SEARCH_LIMIT);
    List<ConversationSearchResult> results = new ArrayList<>();
    results.addAll(searchPersistentLogs(query, normalizedScope, normalizedSpeaker, timeRange));
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
    LinkedHashMap<String, ConversationSearchResult> unique = new LinkedHashMap<>();
    for (ConversationSearchResult result : results) {
      String key =
          String.join(
              "\u0000",
              result.conversationId(),
              result.speaker(),
              result.timestamp(),
              result.content());
      unique.putIfAbsent(key, result);
    }
    return unique.values().stream().limit(safeLimit).toList();
  }

  /**
   * 指定された検索条件に合う情報を探し、候補を返す。
   *
   * <p>Explicit ownership is captured at the tool boundary; retrieval never switches
   * ProjectService.
   */
  public List<ConversationSearchResult> search(HistorySearchRequest request) {
    return search(request, true);
  }

  /**
   * 依頼に関連する情報を得るため、対象シェルを検索する。
   *
   * <p>Shell reads must not append retrieval events or inherit the active AgentRun's ownership.
   */
  public List<ConversationSearchResult> searchForShell(HistorySearchRequest request) {
    return search(request, false);
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  private List<ConversationSearchResult> search(HistorySearchRequest request, boolean emitEvents) {
    String category = normalizeScope(request.conversationScope());
    String speaker = normalizeSpeaker(request.speaker());
    TimeRange range = parseTimeRange(request.since(), request.until());
    int limit = normalizeLimit(request.limit(), DEFAULT_SEARCH_LIMIT, MAX_SEARCH_LIMIT);
    var outcome =
        projectRetrieval.search(
            request,
            e ->
                (category.equals("all") || category.equals(e.scope()))
                    && (speaker == null || speaker.equalsIgnoreCase(e.speaker()))
                    && (range.sinceEpochMillis() == null
                        || e.timestamp().toInstant().toEpochMilli() >= range.sinceEpochMillis())
                    && (range.untilEpochMillis() == null
                        || e.timestamp().toInstant().toEpochMilli() <= range.untilEpochMillis()),
            limit);
    if (emitEvents && events != null && publisher != null) {
      int currentHits =
          (int)
              outcome.results().stream()
                  .filter(r -> r.sourceProjectId().equals(request.preferredProjectId()))
                  .count();
      publisher.publish(
          events.historySearchCompleted(
              new dev.mikoto2000.rei.event.HistorySearchCompletedPayload(
                  request.preferredProjectId(),
                  request.retrievalScope().name(),
                  outcome.searchedProjectCount(),
                  currentHits,
                  outcome.results().size() - currentHits)));
    }
    return outcome.results();
  }

  /** 会話履歴と受付記録の管理で用いる詳細を返す。 */
  public ConversationHistoryDetail detail(String conversationId, Integer limit) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId must not be blank");
    }
    int safeLimit = normalizeLimit(limit, DEFAULT_DETAIL_LIMIT, MAX_DETAIL_LIMIT);
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    if (project != null || conversationId.startsWith("project:")) {
      String id =
          conversationId.startsWith("project:")
              ? conversationId
              : project.conversationId(conversationId);
      String sourceId = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(id);
      var source =
          projectRetrieval.projects().stream()
              .filter(p -> p.id().equals(sourceId))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Unknown source project"));
      boolean foreign = project == null || !source.id().equals(project.id());
      int budget =
          foreign
              ? Math.min(safeLimit, HistoryRetrievalPolicy.CROSS_PROJECT_MAX_RESULTS)
              : safeLimit;
      var entries =
          conversationLogStore.readProject(sourceId).stream()
              .filter(e -> e.conversationId().equals(id))
              .toList();
      var messages =
          entries.stream()
              .skip(Math.max(0, entries.size() - budget))
              .map(
                  e ->
                      new ConversationHistoryMessage(
                          e.speaker(), e.timestamp().toInstant().toString(), e.content()))
              .toList();
      if (messages.isEmpty()) messages = findChatDetail(id, budget);
      if (foreign)
        messages =
            messages.stream()
                .map(
                    m ->
                        new ConversationHistoryMessage(
                            m.speaker(),
                            m.timestamp(),
                            HistoryRetrievalPolicy.clip(
                                m.content(), HistoryRetrievalPolicy.CONTENT_MAX_CHARS)))
                .toList();
      return new ConversationHistoryDetail(
          id,
          ConversationLogStore.scopeOf(id),
          messages,
          source.id(),
          source.name(),
          foreign
              ? HistoryRetrievalPolicy.FOREIGN_BOUNDARY
              : HistoryRetrievalPolicy.LOCAL_BOUNDARY);
    }
    List<ConversationHistoryMessage> persisted = findPersistentLogDetail(conversationId, safeLimit);
    if (!persisted.isEmpty()) {
      return new ConversationHistoryDetail(
          conversationId, ConversationLogStore.scopeOf(conversationId), persisted);
    }
    if (conversationId.startsWith("chat:")) {
      return new ConversationHistoryDetail(
          conversationId,
          "chat",
          findChatDetailWithFallback(conversationId, "chat:".length(), safeLimit));
    }
    if (conversationId.startsWith("bluesky-reply:")) {
      String handle = conversationId.substring("bluesky-reply:".length());
      return new ConversationHistoryDetail(
          conversationId, "bluesky-reply", findBlueskyReplyDetail(handle, safeLimit));
    }
    if (conversationId.startsWith("bluesky-manual:")) {
      return new ConversationHistoryDetail(
          conversationId,
          "bluesky-manual",
          findChatDetailWithFallback(conversationId, "bluesky-manual:".length(), safeLimit));
    }
    if (conversationId.startsWith("tool:")) {
      return new ConversationHistoryDetail(
          conversationId,
          "tool",
          findChatDetailWithFallback(conversationId, "tool:".length(), safeLimit));
    }
    throw new IllegalArgumentException(
        "conversationId must start with chat:, bluesky-reply:, bluesky-manual:, or tool:");
  }

  /** 依頼に関連する情報を得るため、保存されるログ一覧を検索する。 */
  private List<ConversationSearchResult> searchPersistentLogs(
      String query, String scope, String speaker, TimeRange timeRange) {
    List<String> terms = List.of(query.strip().toLowerCase(Locale.ROOT).split("\\s+"));
    return conversationLogStore.readAll().stream()
        .filter(entry -> scope.equals("all") || entry.scope().equals(scope))
        .filter(entry -> speaker == null || entry.speaker().equalsIgnoreCase(speaker))
        .filter(
            entry ->
                timeRange.sinceEpochMillis() == null
                    || entry.timestamp().toInstant().toEpochMilli() >= timeRange.sinceEpochMillis())
        .filter(
            entry ->
                timeRange.untilEpochMillis() == null
                    || entry.timestamp().toInstant().toEpochMilli() <= timeRange.untilEpochMillis())
        .filter(
            entry ->
                terms.stream()
                    .allMatch(term -> entry.content().toLowerCase(Locale.ROOT).contains(term)))
        .map(
            entry ->
                new ConversationSearchResult(
                    entry.conversationId(),
                    entry.scope(),
                    entry.speaker(),
                    entry.timestamp().toInstant().toString(),
                    preview(entry.content(), SUMMARY_MAX_LENGTH),
                    preview(entry.content(), CONTENT_MAX_LENGTH)))
        .toList();
  }

  /** 対象を特定できるよう、保存されるログ詳細を探す。 */
  private List<ConversationHistoryMessage> findPersistentLogDetail(
      String conversationId, int limit) {
    List<ConversationLogEntry> entries =
        conversationLogStore.readAll().stream()
            .filter(entry -> entry.conversationId().equals(conversationId))
            .toList();
    return entries.stream()
        .skip(Math.max(0, entries.size() - limit))
        .map(
            entry ->
                new ConversationHistoryMessage(
                    entry.speaker(), entry.timestamp().toInstant().toString(), entry.content()))
        .toList();
  }

  /** 依頼に関連する情報を得るため、チャットを検索する。 */
  private List<ConversationSearchResult> searchChat(
      String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
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
      return jdbcClient
          .sql(sql.toString())
          .params(params)
          .query(
              (rs, rowNum) -> {
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

  /** 依頼に関連する情報を得るため、BlueskyManualを検索する。 */
  private List<ConversationSearchResult> searchBlueskyManual(
      String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
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
      return jdbcClient
          .sql(sql.toString())
          .params(params)
          .query(
              (rs, rowNum) -> {
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

  /** 依頼に関連する情報を得るため、ツールを検索する。 */
  private List<ConversationSearchResult> searchTool(
      String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
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
      return jdbcClient
          .sql(sql.toString())
          .params(params)
          .query(
              (rs, rowNum) -> {
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

  /** 依頼に関連する情報を得るため、Bluesky返信を検索する。 */
  private List<ConversationSearchResult> searchBlueskyReply(
      String query, String speaker, TimeRange timeRange, int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
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
      return jdbcClient
          .sql(sql.toString())
          .params(params)
          .query(
              (rs, rowNum) -> {
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

  /** 対象を特定できるよう、チャット詳細を探す。 */
  private List<ConversationHistoryMessage> findChatDetail(String conversationId, int limit) {
    try {
      List<ConversationHistoryMessage> messages =
          jdbcClient
              .sql(
                  """
          SELECT type, content, timestamp
          FROM SPRING_AI_CHAT_MEMORY
          WHERE conversation_id = ?
          ORDER BY timestamp DESC
          LIMIT ?
          """)
              .params(conversationId, limit)
              .query(
                  (rs, rowNum) ->
                      new ConversationHistoryMessage(
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

  /** 対象を特定できるよう、チャット詳細付き代替手段を探す。 */
  private List<ConversationHistoryMessage> findChatDetailWithFallback(
      String conversationId, int prefixLength, int limit) {
    List<ConversationHistoryMessage> messages = findChatDetail(conversationId, limit);
    if (!messages.isEmpty()) {
      return messages;
    }
    return findChatDetail(conversationId.substring(prefixLength), limit);
  }

  /** 対象を特定できるよう、Bluesky返信詳細を探す。 */
  private List<ConversationHistoryMessage> findBlueskyReplyDetail(String handle, int limit) {
    try {
      List<ConversationHistoryMessage> messages =
          jdbcClient
              .sql(
                  """
          SELECT role, content, created_at
          FROM bluesky_reply_conversations
          WHERE handle = ?
          ORDER BY id DESC
          LIMIT ?
          """)
              .params(handle, limit)
              .query(
                  (rs, rowNum) ->
                      new ConversationHistoryMessage(
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

  /** 新たな対象を管理できるよう、検索条件条件一覧を追加する。 */
  private void addQueryConditions(StringBuilder sql, List<Object> params, String query) {
    for (String term : query.strip().split("\\s+")) {
      if (term.isBlank()) {
        continue;
      }
      sql.append(" AND lower(content) LIKE ?\n");
      params.add("%" + term.toLowerCase(Locale.ROOT) + "%");
    }
  }

  /** 同じ意味の指定を共通に扱えるよう、対象範囲の表記をそろえる。 */
  private String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "all";
    }
    String normalized = scope.toLowerCase(Locale.ROOT);
    if (normalized.equals("all")
        || normalized.equals("chat")
        || normalized.equals("bluesky-reply")
        || normalized.equals("bluesky-manual")
        || normalized.equals("tool")) {
      return normalized;
    }
    throw new IllegalArgumentException(
        "scope must be all, chat, bluesky-reply, bluesky-manual, or tool");
  }

  /** 同じ意味の指定を共通に扱えるよう、読み上げ先の表記をそろえる。 */
  private String normalizeSpeaker(String speaker) {
    if (speaker == null || speaker.isBlank()) {
      return null;
    }
    return speaker.toLowerCase(Locale.ROOT);
  }

  /** 会話履歴と受付記録の管理で用いるチャット読み上げ先を返す。 */
  private String chatSpeaker(String speaker) {
    if (speaker.equals("assistant")
        || speaker.equals("user")
        || speaker.equals("system")
        || speaker.equals("tool")) {
      return speaker;
    }
    return speaker;
  }

  /** 同じ意味の指定を共通に扱えるよう、チャット読み上げ先の表記をそろえる。 */
  private String normalizeChatSpeaker(String type) {
    return type == null ? "" : type.toLowerCase(Locale.ROOT);
  }

  /** 同じ意味の指定を共通に扱えるよう、上限の表記をそろえる。 */
  private int normalizeLimit(Integer limit, int defaultLimit, int maxLimit) {
    if (limit == null || limit <= 0) {
      return defaultLimit;
    }
    return Math.min(limit, maxLimit);
  }

  /** 入力を機能内で扱えるよう、時刻対象範囲を解釈する。 */
  private TimeRange parseTimeRange(String since, String until) {
    OffsetDateTime sinceTime = parseDateTime(since, false);
    OffsetDateTime untilTime = parseDateTime(until, true);
    return new TimeRange(
        sinceTime == null ? null : sinceTime.toInstant().toEpochMilli(),
        untilTime == null ? null : untilTime.toInstant().toEpochMilli(),
        sinceTime == null ? null : sinceTime.toString(),
        untilTime == null ? null : untilTime.toString());
  }

  /** 入力を機能内で扱えるよう、日付時刻を解釈する。 */
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

  /** 会話履歴と受付記録の管理で用いる事前確認を返す。 */
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

  /** 同じ意味の指定を共通に扱えるよう、記録日時の表記をそろえる。 */
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

  /**
   * 会話履歴と受付記録の管理で受け渡す時刻対象範囲を表す。
   *
   * @param sinceEpochMillis 開始日時基準時刻ミリ秒数
   * @param untilEpochMillis 終了日時基準時刻ミリ秒数
   * @param sinceText 開始日時テキスト
   * @param untilText 終了日時テキスト
   */
  private record TimeRange(
      Long sinceEpochMillis, Long untilEpochMillis, String sinceText, String untilText) {}
}
