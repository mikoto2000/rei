package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 記憶のユースケースを実現する。 */
@Service
public class MemoryService {

  /** データベースへの接続クライアント。 */
  private final JdbcClient jdbcClient;

  /** 記憶設定。 */
  private final MemoryProperties memoryProperties;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 長期記憶の保存と検索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public MemoryService(
      @Qualifier("memoryConsolidationDataSource") DataSource dataSource,
      MemoryProperties memoryProperties) {
    this(dataSource, memoryProperties, Clock.systemUTC());
  }

  /** 指定された値や利用先を使って、MemoryServiceを初期化する。 */
  @Autowired
  public MemoryService(
      @Qualifier("memoryConsolidationDataSource") DataSource dataSource,
      MemoryProperties memoryProperties,
      Clock clock) {
    this.jdbcClient = JdbcClient.create(dataSource);
    this.memoryProperties = memoryProperties;
    this.clock = clock;
    initializeSchema();
  }

  /** 長期記憶の保存と検索に関する初期化データ形式を処理する。 */
  public void initializeSchema() {
    executeDb(
        () -> {
          jdbcClient
              .sql(
                  """
          CREATE TABLE IF NOT EXISTS memories (
            id TEXT PRIMARY KEY,
            content TEXT NOT NULL,
            type TEXT NOT NULL,
            scope TEXT NOT NULL,
            status TEXT NOT NULL,
            confidence REAL NOT NULL,
            expires_at TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            observed_at TEXT,
            valid_until TEXT
          )
          """)
              .update();
          addColumnIfMissing("memories", "observed_at", "TEXT");
          addColumnIfMissing("memories", "valid_until", "TEXT");
          jdbcClient
              .sql(
                  "CREATE TABLE IF NOT EXISTS memory_tags (memory_id TEXT NOT NULL, tag TEXT NOT NULL)")
              .update();
          jdbcClient
              .sql(
                  "CREATE TABLE IF NOT EXISTS memory_sources (memory_id TEXT NOT NULL, source TEXT NOT NULL)")
              .update();
          jdbcClient
              .sql(
                  "CREATE TABLE IF NOT EXISTS memory_relations (from_memory_id TEXT NOT NULL, to_memory_id TEXT NOT NULL, relation_type TEXT NOT NULL)")
              .update();
          jdbcClient
              .sql(
                  "CREATE TABLE IF NOT EXISTS memory_summaries (memory_id TEXT NOT NULL, summary TEXT NOT NULL)")
              .update();
          jdbcClient
              .sql(
                  "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(memory_id UNINDEXED, content)")
              .update();
        },
        "メモリDBの初期化に失敗しました");
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public Memory save(Memory memory) {
    return executeDb(
        () -> {
          OffsetDateTime now = OffsetDateTime.now(clock);
          String id =
              memory.id() == null || memory.id().isBlank()
                  ? UUID.randomUUID().toString()
                  : memory.id();
          OffsetDateTime expiresAt =
              memory.expiresAt() != null ? memory.expiresAt() : defaultExpiry(memory.scope(), now);
          OffsetDateTime observedAt =
              memory.observedAt() != null
                  ? memory.observedAt()
                  : memory.createdAt() != null ? memory.createdAt() : now;
          OffsetDateTime validUntil = memory.validUntil() != null ? memory.validUntil() : expiresAt;
          Memory saved =
              new Memory(
                  id,
                  memory.content(),
                  memory.type(),
                  memory.scope(),
                  MemoryStatus.ACTIVE,
                  memory.confidence(),
                  expiresAt,
                  memory.createdAt() != null ? memory.createdAt() : now,
                  now,
                  observedAt,
                  validUntil);
          jdbcClient
              .sql(
                  """
          INSERT INTO memories(id, content, type, scope, status, confidence, expires_at, created_at, updated_at, observed_at, valid_until)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """)
              .params(
                  saved.id(),
                  saved.content(),
                  saved.type().name(),
                  saved.scope().name(),
                  saved.status().name(),
                  saved.confidence(),
                  toDbTime(saved.expiresAt()),
                  toDbTime(saved.createdAt()),
                  toDbTime(saved.updatedAt()),
                  toDbTime(saved.observedAt()),
                  toDbTime(saved.validUntil()))
              .update();
          jdbcClient
              .sql("INSERT INTO memory_fts(memory_id, content) VALUES (?, ?)")
              .params(saved.id(), saved.content())
              .update();
          return saved;
        },
        "メモリ保存に失敗しました");
  }

  /** 対象を特定できるよう、別識別子を探す。 */
  public Optional<Memory> findById(String id) {
    return executeDb(
        () ->
            jdbcClient
                .sql("SELECT * FROM memories WHERE id = ?")
                .param(id)
                .query(this::mapMemory)
                .optional(),
        "メモリ取得に失敗しました");
  }

  /** 利用可能な対象を確認できるよう、活動中を一覧にする。 */
  public List<Memory> listActive() {
    return executeDb(
        () ->
            jdbcClient
                .sql("SELECT * FROM memories WHERE status = 'ACTIVE' ORDER BY created_at DESC")
                .query(this::mapMemory)
                .list(),
        "アクティブメモリ一覧の取得に失敗しました");
  }

  /** 利用可能な対象を確認できるよう、活動中付き有効期限確認を一覧にする。 */
  public List<Memory> listActiveWithExpiryCheck() {
    executeDb(
        () ->
            jdbcClient
                .sql(
                    """
        UPDATE memories
        SET status = 'DEPRECATED', updated_at = ?
        WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?
        """)
                .params(toDbTime(OffsetDateTime.now(clock)), toDbTime(OffsetDateTime.now(clock)))
                .update(),
        "有効期限チェックに失敗しました");
    return listActive();
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<Memory> search(String query, int limit) {
    int safeLimit = Math.max(1, Math.min(limit, memoryProperties.searchMaxResults()));
    return executeDb(
        () ->
            jdbcClient
                .sql(
                    """
        SELECT m.*
        FROM memory_fts f
        JOIN memories m ON m.id = f.memory_id
        WHERE memory_fts MATCH ? AND m.status = 'ACTIVE'
        ORDER BY rank
        LIMIT ?
        """)
                .params(query, safeLimit)
                .query(this::mapMemory)
                .list(),
        "メモリ検索に失敗しました");
  }

  /** 最新の内容を後続の処理に反映するため、状態を更新する。 */
  public boolean updateStatus(String id, MemoryStatus status) {
    return executeDb(
        () -> {
          int updated =
              jdbcClient
                  .sql("UPDATE memories SET status = ?, updated_at = ? WHERE id = ?")
                  .params(status.name(), toDbTime(OffsetDateTime.now(clock)), id)
                  .update();
          return updated > 0;
        },
        "メモリステータス更新に失敗しました");
  }

  /** 後から参照できるよう、タグ一覧を保存する。 */
  public void saveTags(String memoryId, List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return;
    }
    for (String tag : tags) {
      if (tag == null || tag.isBlank()) {
        continue;
      }
      executeDb(
          () ->
              jdbcClient
                  .sql("INSERT INTO memory_tags(memory_id, tag) VALUES (?, ?)")
                  .params(memoryId, tag)
                  .update(),
          "メモリタグ保存に失敗しました");
    }
  }

  /** 対象を特定できるよう、タグ一覧を探す。 */
  public List<String> findTags(String memoryId) {
    return executeDb(
        () ->
            jdbcClient
                .sql("SELECT tag FROM memory_tags WHERE memory_id = ? ORDER BY tag")
                .param(memoryId)
                .query(String.class)
                .list(),
        "メモリタグ取得に失敗しました");
  }

  /** 後から参照できるよう、取得元を保存する。 */
  public void saveSource(String memoryId, String source) {
    if (source == null || source.isBlank()) {
      return;
    }
    executeDb(
        () ->
            jdbcClient
                .sql("INSERT INTO memory_sources(memory_id, source) VALUES (?, ?)")
                .params(memoryId, source)
                .update(),
        "メモリソース保存に失敗しました");
  }

  /** 対象を特定できるよう、取得元一覧を探す。 */
  public List<String> findSources(String memoryId) {
    return executeDb(
        () ->
            jdbcClient
                .sql("SELECT source FROM memory_sources WHERE memory_id = ? ORDER BY source")
                .param(memoryId)
                .query(String.class)
                .list(),
        "メモリソース取得に失敗しました");
  }

  /** 後から参照できるよう、関係を保存する。 */
  public void saveRelation(String fromMemoryId, String toMemoryId, String relationType) {
    if (fromMemoryId == null
        || toMemoryId == null
        || relationType == null
        || fromMemoryId.isBlank()
        || toMemoryId.isBlank()
        || relationType.isBlank()) {
      return;
    }
    executeDb(
        () ->
            jdbcClient
                .sql(
                    "INSERT INTO memory_relations(from_memory_id, to_memory_id, relation_type) VALUES (?, ?, ?)")
                .params(fromMemoryId, toMemoryId, relationType)
                .update(),
        "メモリ関連保存に失敗しました");
  }

  /** 長期記憶の保存と検索で用いる関係件数を返す。 */
  public int relationCount() {
    Integer count =
        executeDb(
            () ->
                jdbcClient
                    .sql("SELECT COUNT(*) FROM memory_relations")
                    .query(Integer.class)
                    .single(),
            "メモリ関連件数の取得に失敗しました");
    return count == null ? 0 : count;
  }

  /** 後から参照できるよう、要約を保存する。 */
  public void saveSummary(String memoryId, String summary) {
    if (summary == null || summary.isBlank()) {
      return;
    }
    executeDb(
        () ->
            jdbcClient
                .sql("INSERT INTO memory_summaries(memory_id, summary) VALUES (?, ?)")
                .params(memoryId, summary)
                .update(),
        "メモリ要約保存に失敗しました");
  }

  /** 対象を特定できるよう、要約を探す。 */
  public Optional<String> findSummary(String memoryId) {
    return executeDb(
        () ->
            jdbcClient
                .sql(
                    "SELECT summary FROM memory_summaries WHERE memory_id = ? ORDER BY rowid DESC LIMIT 1")
                .param(memoryId)
                .query(String.class)
                .optional(),
        "メモリ要約取得に失敗しました");
  }

  /** 長期記憶の保存と検索で用いる対応表記憶を返す。 */
  private Memory mapMemory(ResultSet rs, int rowNum) throws java.sql.SQLException {
    return new Memory(
        rs.getString("id"),
        rs.getString("content"),
        dev.mikoto2000.rei.memory.model.MemoryType.valueOf(rs.getString("type")),
        MemoryScope.valueOf(rs.getString("scope")),
        MemoryStatus.valueOf(rs.getString("status")),
        rs.getDouble("confidence"),
        fromDbTime(rs.getString("expires_at")),
        fromDbTime(rs.getString("created_at")),
        fromDbTime(rs.getString("updated_at")),
        fromDbTime(rs.getString("observed_at")),
        fromDbTime(rs.getString("valid_until")));
  }

  /** 新たな対象を管理できるよう、列条件付き未指定を追加する。 */
  private void addColumnIfMissing(String table, String column, String type) {
    boolean exists =
        jdbcClient
            .sql("PRAGMA table_info(" + table + ")")
            .query((rs, rowNum) -> rs.getString("name"))
            .list()
            .contains(column);
    if (!exists) {
      jdbcClient.sql("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type).update();
    }
  }

  /** 依頼された操作を進めるため、データベースへのアクセスを実行する。 */
  private <T> T executeDb(Supplier<T> supplier, String message) {
    try {
      return supplier.get();
    } catch (Exception e) {
      throw new IllegalStateException(message, e);
    }
  }

  /** 依頼された操作を進めるため、データベースへのアクセスを実行する。 */
  private void executeDb(Runnable runnable, String message) {
    try {
      runnable.run();
    } catch (Exception e) {
      throw new IllegalStateException(message, e);
    }
  }

  /** 長期記憶の保存と検索で用いる既定有効期限を返す。 */
  private OffsetDateTime defaultExpiry(MemoryScope scope, OffsetDateTime base) {
    if (scope == null) {
      return null;
    }
    return switch (scope) {
      case SHORT_TERM -> base.plusDays(memoryProperties.expiry().shortTermDays());
      case LONG_TERM -> base.plusDays(memoryProperties.expiry().longTermDays());
      case PERMANENT -> null;
      default -> null;
    };
  }

  /** 利用先へ渡せるよう、データベース時刻の形式に変換する。 */
  private String toDbTime(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  /** 長期記憶の保存と検索で用いる起点データベース時刻を返す。 */
  private OffsetDateTime fromDbTime(String value) {
    return value == null ? null : OffsetDateTime.parse(value);
  }
}
