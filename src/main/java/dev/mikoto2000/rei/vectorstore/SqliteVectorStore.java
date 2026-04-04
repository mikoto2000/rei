package dev.mikoto2000.rei.vectorstore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
  private final int embeddingDimensions;

  public SqliteVectorStore(DataSource dataSource, EmbeddingModel embeddingModel, JsonMapper objectMapper) {
    this.dataSource = dataSource;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
    this.embeddingDimensions = embeddingModel.dimensions();
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
            JOIN document_chunks_vec c ON c.doc_id = d.doc_id
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
      throw sqliteException("文書一覧の取得に失敗しました", e);
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
    float[] queryEmbedding = normalizeEmbedding(embeddingModel.embed(new Document(request.getQuery())));
    FilterCriteria criteria = request.hasFilterExpression() ? parseFilter(request.getFilterExpression()) : FilterCriteria.empty();
    List<String> queryTerms = lexicalQueryTerms(request.getQuery());
    if (isZeroEmbedding(queryEmbedding)) {
      return lexicalOnlySearch(request, criteria, queryTerms);
    }
    int candidateLimit = queryTerms.isEmpty() ? request.getTopK() : Math.max(request.getTopK() * 4, 20);

    StringBuilder sql = new StringBuilder("""
        SELECT chunk_id, doc_id, source, chunk_index, ingested_at, chunk_text, metadata_json, distance
        FROM document_chunks_vec
        WHERE embedding MATCH ?
          AND k = ?
        """);
    List<Object> params = new ArrayList<>();
    params.add(writeEmbeddingLiteral(queryEmbedding));
    params.add(candidateLimit);
    if (criteria.source() != null) {
      sql.append(" AND source = ?");
      params.add(criteria.source());
    }
    if (criteria.docId() != null) {
      sql.append(" AND doc_id = ?");
      params.add(criteria.docId());
    }

    List<ScoredDocument> scoredDocuments = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql.toString());
        var adjacentStatement = connection.prepareStatement(adjacentLexicalScoreSql(queryTerms))) {
      bindParams(statement, params);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          Object distanceValue = rs.getObject("distance");
          if (distanceValue == null) {
            continue;
          }
          double vectorScore = 1.0d - ((Number) distanceValue).doubleValue();
          double lexicalScore = lexicalScore(queryTerms, rs.getString("chunk_text"));
          if (lexicalScore == 0.0d && !queryTerms.isEmpty()) {
            lexicalScore = adjacentLexicalScore(adjacentStatement, queryTerms, rs.getString("doc_id"), rs.getInt("chunk_index"));
          }
          if (!queryTerms.isEmpty() && lexicalScore == 0.0d) {
            continue;
          }
          double score = hybridScore(vectorScore, lexicalScore, queryTerms);
          if (score > 0.0d && score >= request.getSimilarityThreshold()) {
            Map<String, Object> metadata = readMetadata(rs.getString("metadata_json"));
            metadata.put("docId", rs.getString("doc_id"));
            metadata.put("source", rs.getString("source"));
            metadata.put("chunkIndex", rs.getInt("chunk_index"));
            metadata.put("ingestedAt", rs.getString("ingested_at"));
            scoredDocuments.add(new ScoredDocument(
                Document.builder()
                    .id(rs.getString("chunk_id"))
                    .text(rs.getString("chunk_text"))
                    .metadata(metadata)
                    .score(score)
                    .build(),
                score));
          }
        }
      }
    } catch (SQLException e) {
      throw sqliteException("ベクトル文書の検索に失敗しました", e);
    }

    return scoredDocuments.stream()
        .sorted(Comparator.comparing(ScoredDocument::score).reversed().thenComparing(result -> result.document().getId()))
        .limit(request.getTopK())
        .map(ScoredDocument::document)
        .toList();
  }

  private List<Document> lexicalOnlySearch(SearchRequest request, FilterCriteria criteria, List<String> queryTerms) {
    if (queryTerms.isEmpty()) {
      return List.of();
    }
    List<Object> params = new ArrayList<>();
    StringBuilder sql = new StringBuilder("""
        SELECT chunk_id, doc_id, source, chunk_index, ingested_at, chunk_text, metadata_json
        FROM document_chunks_vec
        WHERE (
        """);
    sql.append(lexicalClause("chunk_text", queryTerms, params));
    sql.append("""
        OR EXISTS (
          SELECT 1
          FROM document_chunks_vec cx
          WHERE cx.doc_id = document_chunks_vec.doc_id
            AND ABS(cx.chunk_index - document_chunks_vec.chunk_index) <= 1
            AND (
        """);
    sql.append(lexicalClause("cx.chunk_text", queryTerms, params));
    sql.append(")))");
    if (criteria.source() != null) {
      sql.append(" AND source = ?");
      params.add(criteria.source());
    }
    if (criteria.docId() != null) {
      sql.append(" AND doc_id = ?");
      params.add(criteria.docId());
    }

    List<ScoredDocument> scoredDocuments = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql.toString());
        var adjacentStatement = connection.prepareStatement(adjacentLexicalScoreSql(queryTerms))) {
      bindParams(statement, params);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          double lexicalScore = lexicalScore(queryTerms, rs.getString("chunk_text"));
          if (lexicalScore == 0.0d) {
            lexicalScore = adjacentLexicalScore(adjacentStatement, queryTerms, rs.getString("doc_id"), rs.getInt("chunk_index"));
          }
          if (lexicalScore == 0.0d || lexicalScore < request.getSimilarityThreshold()) {
            continue;
          }
          Map<String, Object> metadata = readMetadata(rs.getString("metadata_json"));
          metadata.put("docId", rs.getString("doc_id"));
          metadata.put("source", rs.getString("source"));
          metadata.put("chunkIndex", rs.getInt("chunk_index"));
          metadata.put("ingestedAt", rs.getString("ingested_at"));
          scoredDocuments.add(new ScoredDocument(
              Document.builder()
                  .id(rs.getString("chunk_id"))
                  .text(rs.getString("chunk_text"))
                  .metadata(metadata)
                  .score(lexicalScore)
                  .build(),
              lexicalScore));
        }
      }
    } catch (SQLException e) {
      throw sqliteException("ベクトル文書の検索に失敗しました", e);
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
            INSERT INTO document_chunks_vec
              (chunk_id, doc_id, chunk_index, chunk_text, metadata_json, embedding, source, ingested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      for (Document document : documents) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        String docId = requiredStringValue(metadata, "docId");
        String source = requiredStringValue(metadata, "source");
        String ingestedAt = stringValue(metadata.get("ingestedAt"), null);
        int chunkIndex = requiredIntValue(metadata, "chunkIndex");
        float[] embedding = normalizeEmbedding(embeddingModel.embed(document));

        documentRows.putIfAbsent(docId, new DocumentRow(docId, source, ingestedAt));

        chunkStatement.setString(1, document.getId());
        chunkStatement.setString(2, docId);
        chunkStatement.setInt(3, chunkIndex);
        chunkStatement.setString(4, document.getText());
        chunkStatement.setString(5, writeJson(metadata));
        chunkStatement.setString(6, writeEmbeddingLiteral(embedding));
        chunkStatement.setString(7, source);
        chunkStatement.setString(8, ingestedAt);
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
    try (var deleteChunks = connection.prepareStatement("DELETE FROM document_chunks_vec WHERE doc_id = ?");
        var deleteDocument = connection.prepareStatement("DELETE FROM documents WHERE doc_id = ?")) {
      deleteChunks.setString(1, docId);
      deleteChunks.executeUpdate();
      deleteDocument.setString(1, docId);
      return deleteDocument.executeUpdate();
    }
  }

  private void deleteBySource(Connection connection, String source) throws SQLException {
    try (var deleteChunks = connection.prepareStatement("DELETE FROM document_chunks_vec WHERE source = ?");
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
    try (var deleteChunks = connection.prepareStatement("DELETE FROM document_chunks_vec WHERE chunk_id = ?")) {
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
        WHERE doc_id NOT IN (SELECT DISTINCT doc_id FROM document_chunks_vec)
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
      if (embeddingDimensions <= 0) {
        throw new IllegalStateException("embedding 次元が不正です: " + embeddingDimensions);
      }
      statement.executeUpdate("""
          CREATE VIRTUAL TABLE IF NOT EXISTS document_chunks_vec USING vec0(
            chunk_id text,
            doc_id text,
            chunk_index integer,
            source text,
            ingested_at text,
            embedding float[%d] distance_metric=cosine,
            +chunk_text text,
            +metadata_json text
          )
          """.formatted(embeddingDimensions));
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_documents_source ON documents(source)");
    } catch (SQLException e) {
      throw sqliteException("vector store テーブルの初期化に失敗しました", e);
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

  private String stringValue(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }

  private String requiredStringValue(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    String stringValue = value == null ? "" : value.toString().trim();
    if (stringValue.isEmpty()) {
      throw new IllegalStateException("metadata に必須項目がありません: " + key);
    }
    return stringValue;
  }

  private int requiredIntValue(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalStateException("metadata に必須項目がありません: " + key);
  }

  private float[] normalizeEmbedding(float[] embedding) {
    if (embedding == null || embedding.length == 0) {
      throw new IllegalStateException("embedding が空です");
    }
    double norm = 0.0d;
    for (float value : embedding) {
      norm += value * value;
    }
    if (norm == 0.0d) {
      return embedding.clone();
    }
    float[] normalized = new float[embedding.length];
    double sqrt = Math.sqrt(norm);
    for (int i = 0; i < embedding.length; i++) {
      normalized[i] = (float) (embedding[i] / sqrt);
    }
    return normalized;
  }

  private boolean isZeroEmbedding(float[] embedding) {
    for (float value : embedding) {
      if (value != 0.0f) {
        return false;
      }
    }
    return true;
  }

  private List<String> lexicalQueryTerms(String query) {
    if (query == null || query.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+"))
        .map(String::trim)
        .filter(term -> term.length() >= 2)
        .distinct()
        .toList();
  }

  private double lexicalScore(List<String> queryTerms, String text) {
    if (queryTerms.isEmpty() || text == null || text.isBlank()) {
      return 0.0d;
    }
    String lowerText = text.toLowerCase(Locale.ROOT);
    long matches = queryTerms.stream()
        .filter(lowerText::contains)
        .count();
    return (double) matches / (double) queryTerms.size();
  }

  private String lexicalClause(String textExpression, List<String> queryTerms, List<Object> params) {
    List<String> lexicalConditions = new ArrayList<>();
    for (String queryTerm : queryTerms) {
      lexicalConditions.add("LOWER(" + textExpression + ") LIKE ?");
      params.add("%" + queryTerm + "%");
    }
    return String.join(" OR ", lexicalConditions);
  }

  private String adjacentLexicalScoreSql(List<String> queryTerms) {
    if (queryTerms.isEmpty()) {
      return "SELECT 0";
    }
    List<String> lexicalMatches = new ArrayList<>();
    for (int i = 0; i < queryTerms.size(); i++) {
      lexicalMatches.add("CASE WHEN LOWER(chunk_text) LIKE ? THEN 1 ELSE 0 END");
    }
    return "SELECT MAX((" + String.join(" + ", lexicalMatches) + ") * 1.0 / " + queryTerms.size() + ") "
        + "FROM document_chunks_vec WHERE doc_id = ? AND ABS(chunk_index - ?) <= 1";
  }

  private void bindParams(java.sql.PreparedStatement statement, List<Object> params) throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      statement.setObject(i + 1, params.get(i));
    }
  }

  private double adjacentLexicalScore(
      java.sql.PreparedStatement statement,
      List<String> queryTerms,
      String docId,
      int chunkIndex) throws SQLException {
    if (queryTerms.isEmpty()) {
      return 0.0d;
    }
    int parameterIndex = 1;
    for (String queryTerm : queryTerms) {
      statement.setString(parameterIndex++, "%" + queryTerm + "%");
    }
    statement.setString(parameterIndex++, docId);
    statement.setInt(parameterIndex, chunkIndex);
    try (var rs = statement.executeQuery()) {
      if (!rs.next()) {
        return 0.0d;
      }
      return rs.getDouble(1) * 0.25d;
    }
  }

  private double hybridScore(double vectorScore, double lexicalScore, List<String> queryTerms) {
    if (queryTerms.isEmpty()) {
      return vectorScore;
    }
    return (vectorScore * 0.8d) + (lexicalScore * 0.2d);
  }

  private String writeEmbeddingLiteral(float[] embedding) {
    StringBuilder builder = new StringBuilder("[");
    for (int i = 0; i < embedding.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append(Float.toString(embedding[i]));
    }
    return builder.append(']').toString();
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
        if (e instanceof IllegalStateException illegalStateException) {
          throw illegalStateException;
        }
        throw new IllegalStateException("SQLite 更新に失敗しました", e);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw sqliteException("SQLite への接続に失敗しました", e);
    }
  }

  private IllegalStateException sqliteException(String fallbackMessage, SQLException exception) {
    String message = exception.getMessage();
    if (message != null) {
      String lower = message.toLowerCase();
      if (lower.contains("locked")) {
        return new IllegalStateException("SQLite がロックされています", exception);
      }
      if (lower.contains("not a database") || lower.contains("malformed")) {
        return new IllegalStateException("SQLite ファイルが破損しているか、SQLite 形式ではありません", exception);
      }
      if (lower.contains("dimension")) {
        return new IllegalStateException("embedding 次元が一致しません", exception);
      }
    }
    return new IllegalStateException(fallbackMessage, exception);
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
