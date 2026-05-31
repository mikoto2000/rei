package dev.mikoto2000.rei.memory.service;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;

@Service
public class MemoryService {

  private final JdbcClient jdbcClient;
  private final MemoryProperties memoryProperties;

  public MemoryService(@Qualifier("memoryConsolidationDataSource") DataSource dataSource,
      MemoryProperties memoryProperties) {
    this.jdbcClient = JdbcClient.create(dataSource);
    this.memoryProperties = memoryProperties;
    initializeSchema();
  }

  public void initializeSchema() {
    jdbcClient.sql("""
        CREATE TABLE IF NOT EXISTS memories (
          id TEXT PRIMARY KEY,
          content TEXT NOT NULL,
          type TEXT NOT NULL,
          scope TEXT NOT NULL,
          status TEXT NOT NULL,
          confidence REAL NOT NULL,
          expires_at TEXT,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """).update();
    jdbcClient.sql("CREATE TABLE IF NOT EXISTS memory_tags (memory_id TEXT NOT NULL, tag TEXT NOT NULL)").update();
    jdbcClient.sql("CREATE TABLE IF NOT EXISTS memory_sources (memory_id TEXT NOT NULL, source TEXT NOT NULL)").update();
    jdbcClient.sql("CREATE TABLE IF NOT EXISTS memory_relations (from_memory_id TEXT NOT NULL, to_memory_id TEXT NOT NULL, relation_type TEXT NOT NULL)").update();
    jdbcClient.sql("CREATE TABLE IF NOT EXISTS memory_summaries (memory_id TEXT NOT NULL, summary TEXT NOT NULL)").update();
    jdbcClient.sql("CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(memory_id UNINDEXED, content)").update();
  }

  public Memory save(Memory memory) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String id = memory.id() == null || memory.id().isBlank() ? UUID.randomUUID().toString() : memory.id();
    OffsetDateTime expiresAt = memory.expiresAt() != null ? memory.expiresAt() : defaultExpiry(memory.scope(), now);
    Memory saved = new Memory(
        id,
        memory.content(),
        memory.type(),
        memory.scope(),
        MemoryStatus.ACTIVE,
        memory.confidence(),
        expiresAt,
        memory.createdAt() != null ? memory.createdAt() : now,
        now);
    jdbcClient.sql("""
        INSERT INTO memories(id, content, type, scope, status, confidence, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        .params(saved.id(), saved.content(), saved.type().name(), saved.scope().name(), saved.status().name(), saved.confidence(),
            toDbTime(saved.expiresAt()), toDbTime(saved.createdAt()), toDbTime(saved.updatedAt()))
        .update();
    jdbcClient.sql("INSERT INTO memory_fts(memory_id, content) VALUES (?, ?)")
        .params(saved.id(), saved.content()).update();
    return saved;
  }

  public Optional<Memory> findById(String id) {
    return jdbcClient.sql("SELECT * FROM memories WHERE id = ?")
        .param(id)
        .query(this::mapMemory)
        .optional();
  }

  public List<Memory> listActive() {
    return jdbcClient.sql("SELECT * FROM memories WHERE status = 'ACTIVE' ORDER BY created_at DESC")
        .query(this::mapMemory)
        .list();
  }

  public List<Memory> listActiveWithExpiryCheck() {
    jdbcClient.sql("""
        UPDATE memories
        SET status = 'DEPRECATED', updated_at = ?
        WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < ?
        """)
        .params(toDbTime(OffsetDateTime.now(ZoneOffset.UTC)), toDbTime(OffsetDateTime.now(ZoneOffset.UTC)))
        .update();
    return listActive();
  }

  public List<Memory> search(String query, int limit) {
    int safeLimit = Math.max(1, Math.min(limit, memoryProperties.searchMaxResults()));
    return jdbcClient.sql("""
        SELECT m.*
        FROM memory_fts f
        JOIN memories m ON m.id = f.memory_id
        WHERE memory_fts MATCH ? AND m.status = 'ACTIVE'
        ORDER BY rank
        LIMIT ?
        """)
        .params(query, safeLimit)
        .query(this::mapMemory)
        .list();
  }

  public boolean updateStatus(String id, MemoryStatus status) {
    int updated = jdbcClient.sql("UPDATE memories SET status = ?, updated_at = ? WHERE id = ?")
        .params(status.name(), toDbTime(OffsetDateTime.now(ZoneOffset.UTC)), id)
        .update();
    return updated > 0;
  }

  public void saveTags(String memoryId, List<String> tags) {
    if (tags == null || tags.isEmpty()) {
      return;
    }
    for (String tag : tags) {
      if (tag == null || tag.isBlank()) {
        continue;
      }
      jdbcClient.sql("INSERT INTO memory_tags(memory_id, tag) VALUES (?, ?)")
          .params(memoryId, tag)
          .update();
    }
  }

  public List<String> findTags(String memoryId) {
    return jdbcClient.sql("SELECT tag FROM memory_tags WHERE memory_id = ? ORDER BY tag")
        .param(memoryId)
        .query(String.class)
        .list();
  }

  public void saveSource(String memoryId, String source) {
    if (source == null || source.isBlank()) {
      return;
    }
    jdbcClient.sql("INSERT INTO memory_sources(memory_id, source) VALUES (?, ?)")
        .params(memoryId, source)
        .update();
  }

  public List<String> findSources(String memoryId) {
    return jdbcClient.sql("SELECT source FROM memory_sources WHERE memory_id = ? ORDER BY source")
        .param(memoryId)
        .query(String.class)
        .list();
  }

  public void saveRelation(String fromMemoryId, String toMemoryId, String relationType) {
    if (fromMemoryId == null || toMemoryId == null || relationType == null
        || fromMemoryId.isBlank() || toMemoryId.isBlank() || relationType.isBlank()) {
      return;
    }
    jdbcClient.sql("INSERT INTO memory_relations(from_memory_id, to_memory_id, relation_type) VALUES (?, ?, ?)")
        .params(fromMemoryId, toMemoryId, relationType)
        .update();
  }

  public int relationCount() {
    Integer count = jdbcClient.sql("SELECT COUNT(*) FROM memory_relations")
        .query(Integer.class)
        .single();
    return count == null ? 0 : count;
  }

  public void saveSummary(String memoryId, String summary) {
    if (summary == null || summary.isBlank()) {
      return;
    }
    jdbcClient.sql("INSERT INTO memory_summaries(memory_id, summary) VALUES (?, ?)")
        .params(memoryId, summary)
        .update();
  }

  public Optional<String> findSummary(String memoryId) {
    return jdbcClient.sql("SELECT summary FROM memory_summaries WHERE memory_id = ? ORDER BY rowid DESC LIMIT 1")
        .param(memoryId)
        .query(String.class)
        .optional();
  }

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
        fromDbTime(rs.getString("updated_at")));
  }

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

  private String toDbTime(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private OffsetDateTime fromDbTime(String value) {
    return value == null ? null : OffsetDateTime.parse(value);
  }
}
