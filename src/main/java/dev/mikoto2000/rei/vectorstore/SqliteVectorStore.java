package dev.mikoto2000.rei.vectorstore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class SqliteVectorStore implements VectorStore, VectorDocumentRepository {

  private final DataSource dataSource;
  private final EmbeddingModel embeddingModel;
  private final JsonMapper objectMapper;

  public SqliteVectorStore(DataSource dataSource, EmbeddingModel embeddingModel, JsonMapper objectMapper) {
    this.dataSource = dataSource;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
    initializeSchema();
  }

  @Override
  public void add(List<Document> documents) {
    withTransaction(connection -> {
      insertDocuments(connection, documents);
      return null;
    });
  }

  @Override
  public VectorDocumentEntry replaceBySource(String docId, String source, String ingestedAt, List<Document> documents) {
    withTransaction(connection -> {
      deleteBySource(connection, source);
      insertDocuments(connection, documents);
      return null;
    });
    return new VectorDocumentEntry(docId, source, documents.size(), ingestedAt);
  }

  @Override
  public List<VectorDocumentEntry> list() {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement("""
            SELECT d.doc_id, d.source, COUNT(c.chunk_id) AS chunk_count, d.ingested_at
            FROM documents d
            JOIN document_chunks c ON c.doc_id = d.doc_id
            GROUP BY d.doc_id, d.source, d.ingested_at
            ORDER BY d.source ASC, d.doc_id ASC
            """)) {
      try (var rs = statement.executeQuery()) {
        List<VectorDocumentEntry> entries = new ArrayList<>();
        while (rs.next()) {
          entries.add(new VectorDocumentEntry(
              rs.getString("doc_id"),
              rs.getString("source"),
              rs.getInt("chunk_count"),
              rs.getString("ingested_at")));
        }
        return entries;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("文書一覧の取得に失敗しました", e);
    }
  }

  @Override
  public boolean deleteByDocId(String docId) {
    return withTransaction(connection -> deleteByDocId(connection, docId) > 0);
  }

  @Override
  public int deleteBySource(String source) {
    return withTransaction(connection -> {
      int count = countDocumentsBySource(connection, source);
      deleteBySource(connection, source);
      return count;
    });
  }

  @Override
  public void delete(List<String> ids) {
    withTransaction(connection -> {
      deleteChunks(connection, ids);
      deleteOrphanDocuments(connection);
      return null;
    });
  }

  @Override
  public void delete(Filter.Expression filterExpression) {
    FilterCriteria criteria = parseFilter(filterExpression);
    withTransaction(connection -> {
      deleteMatching(connection, criteria);
      return null;
    });
  }

  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    float[] queryEmbedding = embeddingModel.embed(new Document(request.getQuery()));
    FilterCriteria criteria = request.hasFilterExpression() ? parseFilter(request.getFilterExpression()) : FilterCriteria.empty();
    List<Object> params = new ArrayList<>();
    StringBuilder sql = new StringBuilder("""
        SELECT c.chunk_id, c.chunk_index, c.text, c.metadata_json, c.embedding_json,
               d.doc_id, d.source, d.ingested_at
        FROM document_chunks c
        JOIN documents d ON d.doc_id = c.doc_id
        """);
    appendWhereClause(sql, params, criteria);
    sql.append(" ORDER BY c.chunk_id ASC");

    List<ScoredDocument> scoredDocuments = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql.toString())) {
      bindParams(statement, params);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          float[] embedding = readEmbedding(rs.getString("embedding_json"));
          if (embedding.length != queryEmbedding.length) {
            throw new IllegalStateException("embedding 次元が一致しません");
          }
          double score = cosineSimilarity(queryEmbedding, embedding);
          if (score > 0.0d && score >= request.getSimilarityThreshold()) {
            Map<String, Object> metadata = readMetadata(rs.getString("metadata_json"));
            metadata.put("docId", rs.getString("doc_id"));
            metadata.put("source", rs.getString("source"));
            metadata.put("chunkIndex", rs.getInt("chunk_index"));
            metadata.put("ingestedAt", rs.getString("ingested_at"));
            Document document = Document.builder()
                .id(rs.getString("chunk_id"))
                .text(rs.getString("text"))
                .metadata(metadata)
                .score(score)
                .build();
            scoredDocuments.add(new ScoredDocument(document, score));
          }
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException("ベクトル文書の検索に失敗しました", e);
    }

    return scoredDocuments.stream()
        .sorted(Comparator.comparing(ScoredDocument::score).reversed().thenComparing(result -> result.document().getId()))
        .limit(request.getTopK())
        .map(ScoredDocument::document)
        .toList();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getNativeClient() {
    return Optional.of((T) dataSource);
  }

  private void insertDocuments(Connection connection, List<Document> documents) throws SQLException {
    Map<String, DocumentRow> documentRows = new LinkedHashMap<>();
    try (var documentStatement = connection.prepareStatement("""
        INSERT INTO documents (doc_id, source, ingested_at)
        VALUES (?, ?, ?)
        """);
        var chunkStatement = connection.prepareStatement("""
            INSERT INTO document_chunks
              (chunk_id, doc_id, chunk_index, text, metadata_json, embedding_json, embedding_dim)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
      for (Document document : documents) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        String docId = stringValue(metadata.get("docId"), document.getId());
        String source = stringValue(metadata.get("source"), "");
        String ingestedAt = stringValue(metadata.get("ingestedAt"), null);
        int chunkIndex = intValue(metadata.get("chunkIndex"));
        float[] embedding = embeddingModel.embed(document);

        documentRows.putIfAbsent(docId, new DocumentRow(docId, source, ingestedAt));

        chunkStatement.setString(1, document.getId());
        chunkStatement.setString(2, docId);
        chunkStatement.setInt(3, chunkIndex);
        chunkStatement.setString(4, document.getText());
        chunkStatement.setString(5, writeJson(metadata));
        chunkStatement.setString(6, writeJson(embedding));
        chunkStatement.setInt(7, embedding.length);
        chunkStatement.addBatch();
      }

      for (DocumentRow row : documentRows.values()) {
        documentStatement.setString(1, row.docId());
        documentStatement.setString(2, row.source());
        documentStatement.setString(3, row.ingestedAt());
        documentStatement.addBatch();
      }

      documentStatement.executeBatch();
      chunkStatement.executeBatch();
    }
  }

  private int deleteByDocId(Connection connection, String docId) throws SQLException {
    try (var deleteChunks = connection.prepareStatement("DELETE FROM document_chunks WHERE doc_id = ?");
        var deleteDocument = connection.prepareStatement("DELETE FROM documents WHERE doc_id = ?")) {
      deleteChunks.setString(1, docId);
      deleteChunks.executeUpdate();
      deleteDocument.setString(1, docId);
      return deleteDocument.executeUpdate();
    }
  }

  private void deleteBySource(Connection connection, String source) throws SQLException {
    try (var deleteChunks = connection.prepareStatement("""
        DELETE FROM document_chunks
        WHERE doc_id IN (SELECT doc_id FROM documents WHERE source = ?)
        """);
        var deleteDocuments = connection.prepareStatement("DELETE FROM documents WHERE source = ?")) {
      deleteChunks.setString(1, source);
      deleteChunks.executeUpdate();
      deleteDocuments.setString(1, source);
      deleteDocuments.executeUpdate();
    }
  }

  private void deleteMatching(Connection connection, FilterCriteria criteria) throws SQLException {
    if (criteria.docId() != null && criteria.source() != null) {
      try (var select = connection.prepareStatement("SELECT doc_id FROM documents WHERE doc_id = ? AND source = ?")) {
        select.setString(1, criteria.docId());
        select.setString(2, criteria.source());
        try (var rs = select.executeQuery()) {
          if (rs.next()) {
            deleteByDocId(connection, criteria.docId());
          }
        }
      }
      return;
    }
    if (criteria.docId() != null) {
      deleteByDocId(connection, criteria.docId());
      return;
    }
    if (criteria.source() != null) {
      deleteBySource(connection, criteria.source());
      return;
    }
    throw new UnsupportedOperationException("空の filter 削除は未対応です");
  }

  private int countDocumentsBySource(Connection connection, String source) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM documents WHERE source = ?")) {
      statement.setString(1, source);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  private void deleteChunks(Connection connection, List<String> ids) throws SQLException {
    try (var deleteChunks = connection.prepareStatement("DELETE FROM document_chunks WHERE chunk_id = ?")) {
      for (String id : ids) {
        deleteChunks.setString(1, id);
        deleteChunks.addBatch();
      }
      deleteChunks.executeBatch();
    }
  }

  private void deleteOrphanDocuments(Connection connection) throws SQLException {
    try (var deleteOrphans = connection.prepareStatement("""
        DELETE FROM documents
        WHERE doc_id NOT IN (SELECT DISTINCT doc_id FROM document_chunks)
        """)) {
      deleteOrphans.executeUpdate();
    }
  }

  private FilterCriteria parseFilter(Filter.Expression expression) {
    return switch (expression.type()) {
      case EQ -> eqCriteria(expression);
      case AND -> parseFilter((Filter.Expression) expression.left()).merge(parseFilter((Filter.Expression) expression.right()));
      default -> throw new UnsupportedOperationException("未対応の filter です: " + expression.type());
    };
  }

  private FilterCriteria eqCriteria(Filter.Expression expression) {
    if (!(expression.left() instanceof Filter.Key key) || !(expression.right() instanceof Filter.Value value)) {
      throw new UnsupportedOperationException("未対応の EQ filter です");
    }
    return switch (key.key()) {
      case "source" -> new FilterCriteria(stringValue(value.value(), null), null);
      case "docId" -> new FilterCriteria(null, stringValue(value.value(), null));
      default -> throw new UnsupportedOperationException("未対応の filter key です: " + key.key());
    };
  }

  private void appendWhereClause(StringBuilder sql, List<Object> params, FilterCriteria criteria) {
    List<String> conditions = new ArrayList<>();
    if (criteria.source() != null) {
      conditions.add("d.source = ?");
      params.add(criteria.source());
    }
    if (criteria.docId() != null) {
      conditions.add("d.doc_id = ?");
      params.add(criteria.docId());
    }
    if (!conditions.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", conditions));
    }
  }

  private void bindParams(java.sql.PreparedStatement statement, List<Object> params) throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      statement.setObject(i + 1, params.get(i));
    }
  }

  private void initializeSchema() {
    try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("PRAGMA foreign_keys = ON");
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS documents (
            doc_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            ingested_at TEXT
          )
          """);
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS document_chunks (
            chunk_id TEXT PRIMARY KEY,
            doc_id TEXT NOT NULL,
            chunk_index INTEGER NOT NULL,
            text TEXT NOT NULL,
            metadata_json TEXT NOT NULL,
            embedding_json TEXT NOT NULL,
            embedding_dim INTEGER NOT NULL,
            FOREIGN KEY (doc_id) REFERENCES documents(doc_id) ON DELETE CASCADE
          )
          """);
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_document_chunks_doc_id ON document_chunks(doc_id)");
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_documents_source ON documents(source)");
    } catch (SQLException e) {
      throw new IllegalStateException("vector store テーブルの初期化に失敗しました", e);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON 変換に失敗しました", e);
    }
  }

  private Map<String, Object> readMetadata(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {
      });
    } catch (Exception e) {
      throw new IllegalStateException("metadata の読み込みに失敗しました", e);
    }
  }

  private float[] readEmbedding(String value) {
    try {
      return objectMapper.readValue(value, float[].class);
    } catch (Exception e) {
      throw new IllegalStateException("embedding の読み込みに失敗しました", e);
    }
  }

  private String stringValue(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }

  private int intValue(Object value) {
    return value instanceof Number number ? number.intValue() : -1;
  }

  private double cosineSimilarity(float[] left, float[] right) {
    double dot = 0.0d;
    double leftNorm = 0.0d;
    double rightNorm = 0.0d;
    for (int i = 0; i < left.length; i++) {
      dot += left[i] * right[i];
      leftNorm += left[i] * left[i];
      rightNorm += right[i] * right[i];
    }
    if (leftNorm == 0.0d || rightNorm == 0.0d) {
      return 0.0d;
    }
    return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  private <T> T withTransaction(SqliteWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (Exception e) {
        connection.rollback();
        throw new IllegalStateException("SQLite 更新に失敗しました", e);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("SQLite への接続に失敗しました", e);
    }
  }

  private record ScoredDocument(Document document, double score) {
  }

  private record DocumentRow(String docId, String source, String ingestedAt) {
  }

  private record FilterCriteria(String source, String docId) {
    static FilterCriteria empty() {
      return new FilterCriteria(null, null);
    }

    FilterCriteria merge(FilterCriteria other) {
      String mergedSource = source != null ? source : other.source;
      String mergedDocId = docId != null ? docId : other.docId;
      if (source != null && other.source != null && !source.equals(other.source)) {
        throw new UnsupportedOperationException("複数 source を含む filter は未対応です");
      }
      if (docId != null && other.docId != null && !docId.equals(other.docId)) {
        throw new UnsupportedOperationException("複数 docId を含む filter は未対応です");
      }
      return new FilterCriteria(mergedSource, mergedDocId);
    }
  }

  @FunctionalInterface
  private interface SqliteWork<T> {
    T run(Connection connection) throws Exception;
  }
}
