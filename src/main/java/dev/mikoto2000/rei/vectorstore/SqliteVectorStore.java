package dev.mikoto2000.rei.vectorstore;

import dev.mikoto2000.rei.vectordocument.VectorDocumentEntry;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQLiteベクトルの記録を保持し、後続の処理で参照できるようにする。 */
public class SqliteVectorStore
    implements VectorStore, VectorDocumentRepository, RetrievalCandidates {

  /** データ取得元。 */
  private final DataSource dataSource;

  /** 埋め込み表現モデル。 */
  private final EmbeddingModel embeddingModel;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper objectMapper;

  /** 埋め込み表現次元数。 */
  private final int embeddingDimensions;

  /** bm25有効状態。 */
  private final boolean bm25Enabled;

  /** 関連文書の検索を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SqliteVectorStore(
      DataSource dataSource, EmbeddingModel embeddingModel, JsonMapper objectMapper) {
    this(dataSource, embeddingModel, objectMapper, false);
  }

  /** 指定された値や利用先を使って、SqliteVectorStoreを初期化する。 */
  public SqliteVectorStore(
      DataSource dataSource,
      EmbeddingModel embeddingModel,
      JsonMapper objectMapper,
      boolean bm25Enabled) {
    this.dataSource = dataSource;
    this.embeddingModel = embeddingModel;
    this.objectMapper = objectMapper;
    this.bm25Enabled = bm25Enabled;
    this.embeddingDimensions = embeddingModel.dimensions();
    initializeSchema();
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @Override
  public void add(List<Document> documents) {
    withTransaction(
        connection -> {
          insertDocuments(connection, documents);
          return null;
        });
  }

  /** 指定された内容へ切り替えるため、別取得元を置き換える。 */
  @Override
  public VectorDocumentEntry replaceBySource(
      String docId, String source, String ingestedAt, List<Document> documents) {
    withTransaction(
        connection -> {
          deleteBySource(connection, source);
          insertDocuments(connection, documents);
          return null;
        });
    return new VectorDocumentEntry(docId, source, documents.size(), ingestedAt);
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @Override
  public List<VectorDocumentEntry> list() {
    try (Connection connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
            SELECT doc_id, source, COUNT(chunk_id) AS chunk_count, ingested_at
            FROM document_chunks_vec
            GROUP BY doc_id, source, ingested_at
            ORDER BY source ASC, doc_id ASC
            """)) {
      try (var rs = statement.executeQuery()) {
        List<VectorDocumentEntry> entries = new ArrayList<>();
        while (rs.next()) {
          entries.add(
              new VectorDocumentEntry(
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

  /** 対象を管理から外すため、別文書の識別子を削除する。 */
  @Override
  public boolean deleteByDocId(String docId) {
    return withTransaction(connection -> deleteByDocId(connection, docId) > 0);
  }

  /** 対象を管理から外すため、別取得元を削除する。 */
  @Override
  public int deleteBySource(String source) {
    return withTransaction(
        connection -> {
          int count = countDocumentsBySource(connection, source);
          deleteBySource(connection, source);
          return count;
        });
  }

  /** 指定された対象を削除する。 */
  @Override
  public void delete(List<String> ids) {
    withTransaction(
        connection -> {
          deleteChunks(connection, ids);
          return null;
        });
  }

  /** 指定された対象を削除する。 */
  @Override
  public void delete(Filter.Expression filterExpression) {
    FilterCriteria criteria = parseFilter(filterExpression);
    withTransaction(
        connection -> {
          deleteMatching(connection, criteria);
          return null;
        });
  }

  /** 関連文書の検索で用いる類似度検索を返す。 */
  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    return rankedSearch(request, false);
  }

  /** 関連文書の検索で用いるベクトルによる検索を返す。 */
  @Override
  public List<Document> denseSearch(SearchRequest request) {
    return rankedSearch(request, true);
  }

  /** 関連文書の検索で用いる語句による検索を返す。 */
  @Override
  public List<Document> lexicalSearch(SearchRequest request) {
    FilterCriteria criteria =
        request.hasFilterExpression()
            ? parseFilter(request.getFilterExpression())
            : FilterCriteria.empty();
    return lexicalOnlySearch(request, criteria, lexicalQueryTerms(request.getQuery()));
  }

  /** 関連文書の検索で用いるbm25検索を返す。 */
  @Override
  public List<Document> bm25Search(SearchRequest request) {
    if (!bm25Enabled) throw new UnsupportedOperationException("BM25 retrieval is disabled");
    checkCancellation();
    if (request.getTopK() < 1 || request.getTopK() > 256)
      throw new IllegalArgumentException("BM25 topK must be 1..256");
    FilterCriteria criteria =
        request.hasFilterExpression()
            ? parseFilter(request.getFilterExpression())
            : FilterCriteria.empty();
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (request.getQuery() != null && request.getQuery().length() > 4096)
      throw new IllegalArgumentException("BM25 query limit exceeded");
    var terms = lexicalQueryTerms(request.getQuery());
    if (terms.isEmpty()) return List.of();
    if (terms.size() > 64) throw new IllegalArgumentException("BM25 query limit exceeded");
    StringBuilder sql =
        new StringBuilder(
            """
        SELECT v.chunk_id, v.doc_id, v.source, v.chunk_index, v.ingested_at, v.chunk_text, v.metadata_json,
               -bm25(document_chunks_fts) AS score
        FROM document_chunks_fts JOIN document_chunks_vec v ON v.chunk_id = document_chunks_fts.chunk_id
        WHERE document_chunks_fts MATCH ?
        """);
    List<Object> params = new ArrayList<>();
    // Terms contain letters/digits only, and quotes ensure OR/NOT are literal query terms.
    params.add(
        terms.stream()
            .map(term -> "\"" + term + "\"")
            .collect(java.util.stream.Collectors.joining(" OR ")));
    if (criteria.source() != null) {
      sql.append(" AND v.source = ?");
      params.add(criteria.source());
    }
    if (criteria.docId() != null) {
      sql.append(" AND v.doc_id = ?");
      params.add(criteria.docId());
    }
    sql.append(" ORDER BY score DESC, v.chunk_id ASC");
    var results = new ArrayList<Document>();
    try (var connection = dataSource.getConnection();
        var statement = connection.prepareStatement(sql.toString())) {
      statement.setQueryTimeout(5);
      bindParams(statement, params);
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          checkCancellation();
          // BM25 has no probability scale. Keep the existing term coverage eligibility threshold.
          double coverage = lexicalScore(terms, rs.getString("chunk_text"));
          if (coverage < request.getSimilarityThreshold()) continue;
          var metadata = readMetadata(rs.getString("metadata_json"));
          metadata.put("docId", rs.getString("doc_id"));
          metadata.put("source", rs.getString("source"));
          metadata.put("chunkIndex", rs.getInt("chunk_index"));
          metadata.put("ingestedAt", rs.getString("ingested_at"));
          metadata.put("lexicalMode", "bm25");
          metadata.put("bm25Score", rs.getDouble("score"));
          metadata.put("lexicalCoverage", coverage);
          results.add(
              Document.builder()
                  .id(rs.getString("chunk_id"))
                  .text(rs.getString("chunk_text"))
                  .metadata(metadata)
                  .score(rs.getDouble("score"))
                  .build());
          if (results.size() == request.getTopK()) break;
        }
      }
    } catch (SQLException e) {
      throw sqliteException("BM25 文書検索に失敗しました", e);
    }
    return List.copyOf(results);
  }

  /** 後続の操作を進められるか判断するため、キャンセルを確認する。 */
  private static void checkCancellation() {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException("BM25 retrieval cancelled");
  }

  /** 関連文書の検索で用いる順位を付けた検索を返す。 */
  private List<Document> rankedSearch(SearchRequest request, boolean denseOnly) {
    float[] queryEmbedding =
        normalizeEmbedding(embeddingModel.embed(new Document(request.getQuery())));
    FilterCriteria criteria =
        request.hasFilterExpression()
            ? parseFilter(request.getFilterExpression())
            : FilterCriteria.empty();
    List<String> queryTerms = denseOnly ? List.of() : lexicalQueryTerms(request.getQuery());
    if (isZeroEmbedding(queryEmbedding)) {
      return denseOnly ? List.of() : lexicalOnlySearch(request, criteria, queryTerms);
    }
    int candidateLimit =
        queryTerms.isEmpty() ? request.getTopK() : Math.max(request.getTopK() * 4, 20);

    StringBuilder sql =
        new StringBuilder(
            """
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
            lexicalScore =
                adjacentLexicalScore(
                    adjacentStatement,
                    queryTerms,
                    rs.getString("doc_id"),
                    rs.getString("source"),
                    rs.getInt("chunk_index"));
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
            scoredDocuments.add(
                new ScoredDocument(
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
        .sorted(
            Comparator.comparing(ScoredDocument::score)
                .reversed()
                .thenComparing(result -> result.document().getId()))
        .limit(request.getTopK())
        .map(ScoredDocument::document)
        .toList();
  }

  /** 関連文書の検索で用いる語句による限定検索を返す。 */
  private List<Document> lexicalOnlySearch(
      SearchRequest request, FilterCriteria criteria, List<String> queryTerms) {
    if (queryTerms.isEmpty()) {
      return List.of();
    }
    List<Object> params = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
        SELECT chunk_id, doc_id, source, chunk_index, ingested_at, chunk_text, metadata_json
        FROM document_chunks_vec
        WHERE (
        """);
    sql.append(lexicalClause("chunk_text", queryTerms, params));
    sql.append(
        """
        OR EXISTS (
          SELECT 1
          FROM document_chunks_vec cx
          WHERE cx.doc_id = document_chunks_vec.doc_id
            AND cx.source = document_chunks_vec.source
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
            lexicalScore =
                adjacentLexicalScore(
                    adjacentStatement,
                    queryTerms,
                    rs.getString("doc_id"),
                    rs.getString("source"),
                    rs.getInt("chunk_index"));
          }
          if (lexicalScore == 0.0d || lexicalScore < request.getSimilarityThreshold()) {
            continue;
          }
          Map<String, Object> metadata = readMetadata(rs.getString("metadata_json"));
          metadata.put("docId", rs.getString("doc_id"));
          metadata.put("source", rs.getString("source"));
          metadata.put("chunkIndex", rs.getInt("chunk_index"));
          metadata.put("ingestedAt", rs.getString("ingested_at"));
          scoredDocuments.add(
              new ScoredDocument(
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
        .sorted(
            Comparator.comparing(ScoredDocument::score)
                .reversed()
                .thenComparing(result -> result.document().getId()))
        .limit(request.getTopK())
        .map(ScoredDocument::document)
        .toList();
  }

  /** 関連文書の検索で参照するOSの機能クライアントを返す。 */
  @Override
  @SuppressWarnings("unchecked")
  public <T> Optional<T> getNativeClient() {
    return Optional.of((T) dataSource);
  }

  /** 関連文書の検索に関する追加保存済み文書を処理する。 */
  private void insertDocuments(Connection connection, List<Document> documents)
      throws SQLException {
    try (var chunkStatement =
        connection.prepareStatement(
            """
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
      chunkStatement.executeBatch();
    }
    if (hasFtsIndex(connection)) {
      try (var statement =
          connection.prepareStatement(
              "INSERT INTO document_chunks_fts(chunk_id, chunk_text) VALUES (?, ?)")) {
        for (var document : documents) {
          statement.setString(1, document.getId());
          statement.setString(2, document.getText());
          statement.addBatch();
        }
        statement.executeBatch();
      }
    }
  }

  /** 対象を管理から外すため、別文書の識別子を削除する。 */
  private int deleteByDocId(Connection connection, String docId) throws SQLException {
    deleteFtsMatching(connection, "doc_id = ?", List.of(docId));
    try (var deleteChunks =
        connection.prepareStatement("DELETE FROM document_chunks_vec WHERE doc_id = ?")) {
      deleteChunks.setString(1, docId);
      return deleteChunks.executeUpdate();
    }
  }

  /** 対象を管理から外すため、別取得元を削除する。 */
  private void deleteBySource(Connection connection, String source) throws SQLException {
    deleteFtsMatching(connection, "source = ?", List.of(source));
    try (var deleteChunks =
        connection.prepareStatement("DELETE FROM document_chunks_vec WHERE source = ?")) {
      deleteChunks.setString(1, source);
      deleteChunks.executeUpdate();
    }
  }

  /** 対象を管理から外すため、Matchingを削除する。 */
  private void deleteMatching(Connection connection, FilterCriteria criteria) throws SQLException {
    if (criteria.docId() != null && criteria.source() != null) {
      deleteFtsMatching(
          connection, "doc_id = ? AND source = ?", List.of(criteria.docId(), criteria.source()));
      try (var deleteChunks =
          connection.prepareStatement(
              "DELETE FROM document_chunks_vec WHERE doc_id = ? AND source = ?")) {
        deleteChunks.setString(1, criteria.docId());
        deleteChunks.setString(2, criteria.source());
        deleteChunks.executeUpdate();
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

  /** 対象の規模を判断するため、保存済み文書別取得元を数える。 */
  private int countDocumentsBySource(Connection connection, String source) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT COUNT(DISTINCT doc_id) FROM document_chunks_vec WHERE source = ?")) {
      statement.setString(1, source);
      try (var rs = statement.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  /** 対象を管理から外すため、文書の分割一覧を削除する。 */
  private void deleteChunks(Connection connection, List<String> ids) throws SQLException {
    for (String id : ids) deleteFtsMatching(connection, "chunk_id = ?", List.of(id));
    try (var deleteChunks =
        connection.prepareStatement("DELETE FROM document_chunks_vec WHERE chunk_id = ?")) {
      for (String id : ids) {
        deleteChunks.setString(1, id);
        deleteChunks.addBatch();
      }
      deleteChunks.executeBatch();
    }
  }

  /** 入力を機能内で扱えるよう、絞り込み条件を解釈する。 */
  private FilterCriteria parseFilter(Filter.Expression expression) {
    return switch (expression.type()) {
      case EQ -> eqCriteria(expression);
      case AND ->
          parseFilter((Filter.Expression) expression.left())
              .merge(parseFilter((Filter.Expression) expression.right()));
      default -> throw new UnsupportedOperationException("未対応の filter です: " + expression.type());
    };
  }

  /** 対象を管理から外すため、FtsMatchingを削除する。 */
  private void deleteFtsMatching(Connection connection, String clause, List<Object> params)
      throws SQLException {
    if (!hasFtsIndex(connection)) return;
    try (var statement =
        connection.prepareStatement(
            "DELETE FROM document_chunks_fts WHERE chunk_id IN (SELECT chunk_id FROM document_chunks_vec WHERE "
                + clause
                + ")")) {
      bindParams(statement, params);
      statement.executeUpdate();
    }
  }

  /** 関連文書の検索において、Fts位置の有無を判定する。 */
  private boolean hasFtsIndex(Connection connection) throws SQLException {
    if (bm25Enabled) return true;
    // Preserve an existing index even when searches use the legacy configuration.
    try (var statement =
            connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='document_chunks_fts'");
        var rs = statement.executeQuery()) {
      return rs.next();
    }
  }

  /** 関連文書の検索で用いるeq判断基準一覧を返す。 */
  private FilterCriteria eqCriteria(Filter.Expression expression) {
    if (!(expression.left() instanceof Filter.Key key)
        || !(expression.right() instanceof Filter.Value value)) {
      throw new UnsupportedOperationException("未対応の EQ filter です");
    }
    return switch (key.key()) {
      case "source" -> new FilterCriteria(stringValue(value.value(), null), null);
      case "docId" -> new FilterCriteria(null, stringValue(value.value(), null));
      default -> throw new UnsupportedOperationException("未対応の filter key です: " + key.key());
    };
  }

  /** 関連文書の検索に関する初期化データ形式を処理する。 */
  private void initializeSchema() {
    try (Connection connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate("PRAGMA foreign_keys = ON");
      if (embeddingDimensions <= 0) {
        throw new IllegalStateException("embedding 次元が不正です: " + embeddingDimensions);
      }
      statement.executeUpdate(
          """
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
          """
              .formatted(embeddingDimensions));
      statement.executeUpdate("DROP TABLE IF EXISTS documents");
      if (bm25Enabled) {
        // Rebuild on activation, including mutations made while the opt-in was disabled.
        connection.setAutoCommit(false);
        try {
          statement.executeUpdate(
              "CREATE VIRTUAL TABLE IF NOT EXISTS document_chunks_fts USING fts5(chunk_id UNINDEXED, chunk_text, tokenize='unicode61')");
          statement.executeUpdate("DELETE FROM document_chunks_fts");
          statement.executeUpdate(
              "INSERT INTO document_chunks_fts(chunk_id, chunk_text) SELECT chunk_id, chunk_text FROM document_chunks_vec");
          connection.commit();
        } catch (SQLException e) {
          connection.rollback();
          throw e;
        } finally {
          connection.setAutoCommit(true);
        }
      }
    } catch (SQLException e) {
      throw sqliteException("vector store テーブルの初期化に失敗しました", e);
    }
  }

  /** 指定された内容を保存先へ反映するため、保存・受け渡しに使うJSON変換を書き込む。 */
  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON 変換に失敗しました", e);
    }
  }

  /** 必要な内容を参照できるよう、付随情報を読み取る。 */
  private Map<String, Object> readMetadata(String value) {
    try {
      return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("metadata の読み込みに失敗しました", e);
    }
  }

  /** 関連文書の検索で用いる文字列表現値を返す。 */
  private String stringValue(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }

  /** 関連文書の検索で用いる必須文字列表現値を返す。 */
  private String requiredStringValue(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    String stringValue = value == null ? "" : value.toString().trim();
    if (stringValue.isEmpty()) {
      throw new IllegalStateException("metadata に必須項目がありません: " + key);
    }
    return stringValue;
  }

  /** 関連文書の検索で用いる必須Int値を返す。 */
  private int requiredIntValue(Map<String, Object> metadata, String key) {
    Object value = metadata.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalStateException("metadata に必須項目がありません: " + key);
  }

  /** 同じ意味の指定を共通に扱えるよう、埋め込み表現の表記をそろえる。 */
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

  /** 関連文書の検索において、ゼロ埋め込み表現かどうかを判定する。 */
  private boolean isZeroEmbedding(float[] embedding) {
    for (float value : embedding) {
      if (value != 0.0f) {
        return false;
      }
    }
    return true;
  }

  /** 関連文書の検索で用いる語句による検索条件検索語句一覧を返す。 */
  private List<String> lexicalQueryTerms(String query) {
    return dev.mikoto2000.rei.core.contextbudget.LexicalTerms.query(query);
  }

  /** 関連文書の検索で用いる語句による評価値を返す。 */
  private double lexicalScore(List<String> queryTerms, String text) {
    if (queryTerms.isEmpty() || text == null || text.isBlank()) {
      return 0.0d;
    }
    String lowerText = text.toLowerCase(Locale.ROOT);
    long matches = queryTerms.stream().filter(lowerText::contains).count();
    return (double) matches / (double) queryTerms.size();
  }

  /** 関連文書の検索で用いる語句によるClauseを返す。 */
  private String lexicalClause(
      String textExpression, List<String> queryTerms, List<Object> params) {
    List<String> lexicalConditions = new ArrayList<>();
    for (String queryTerm : queryTerms) {
      lexicalConditions.add("LOWER(" + textExpression + ") LIKE ?");
      params.add("%" + queryTerm + "%");
    }
    return String.join(" OR ", lexicalConditions);
  }

  /** 関連文書の検索で用いるadjacent語句による評価値データ操作を返す。 */
  private String adjacentLexicalScoreSql(List<String> queryTerms) {
    if (queryTerms.isEmpty()) {
      return "SELECT 0";
    }
    List<String> lexicalMatches = new ArrayList<>();
    for (int i = 0; i < queryTerms.size(); i++) {
      lexicalMatches.add("CASE WHEN LOWER(chunk_text) LIKE ? THEN 1 ELSE 0 END");
    }
    return "SELECT MAX(("
        + String.join(" + ", lexicalMatches)
        + ") * 1.0 / "
        + queryTerms.size()
        + ") "
        + "FROM document_chunks_vec WHERE doc_id = ? AND source = ? AND ABS(chunk_index - ?) <= 1";
  }

  /** 後続の照会で対応する対象を見つけられるよう、引数を関連付ける。 */
  private void bindParams(java.sql.PreparedStatement statement, List<Object> params)
      throws SQLException {
    for (int i = 0; i < params.size(); i++) {
      statement.setObject(i + 1, params.get(i));
    }
  }

  /** 関連文書の検索で用いるadjacent語句による評価値を返す。 */
  private double adjacentLexicalScore(
      java.sql.PreparedStatement statement,
      List<String> queryTerms,
      String docId,
      String source,
      int chunkIndex)
      throws SQLException {
    if (queryTerms.isEmpty()) {
      return 0.0d;
    }
    int parameterIndex = 1;
    for (String queryTerm : queryTerms) {
      statement.setString(parameterIndex++, "%" + queryTerm + "%");
    }
    statement.setString(parameterIndex++, docId);
    statement.setString(parameterIndex++, source);
    statement.setInt(parameterIndex, chunkIndex);
    try (var rs = statement.executeQuery()) {
      if (!rs.next()) {
        return 0.0d;
      }
      return rs.getDouble(1) * 0.25d;
    }
  }

  /** 関連文書の検索で用いるhybrid評価値を返す。 */
  private double hybridScore(double vectorScore, double lexicalScore, List<String> queryTerms) {
    if (queryTerms.isEmpty()) {
      return vectorScore;
    }
    return (vectorScore * 0.8d) + (lexicalScore * 0.2d);
  }

  /** 指定された内容を保存先へ反映するため、埋め込み表現そのままの値を書き込む。 */
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

  /** 指定された一連の更新を反映した情報を作成する。 */
  private <T> T withTransaction(SqliteWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.run(connection);
        connection.commit();
        return result;
      } catch (Exception e) {
        connection.rollback();
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(e);
        if (e instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped)
          throw stopped;
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

  /** 関連文書の検索で用いるSQLite例外を返す。 */
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

  /**
   * 関連文書の検索で受け渡す評価済み文書を表す。
   *
   * @param document 文書
   * @param score 評価値
   */
  private record ScoredDocument(Document document, double score) {}

  /**
   * 関連文書の検索で受け渡す絞り込み条件判断基準一覧を表す。
   *
   * @param source 取得元
   * @param docId 文書の識別子
   */
  private record FilterCriteria(String source, String docId) {
    /** 対象がない場合の結果を表す。 */
    static FilterCriteria empty() {
      return new FilterCriteria(null, null);
    }

    /** 関連する情報をまとめて扱えるよう、絞り込み条件判断基準一覧を統合する。 */
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

  /** 関連文書の検索に必要なSQLite作業を扱う。 */
  @FunctionalInterface
  private interface SqliteWork<T> {
    /** 関連文書の検索の操作を実行する。 */
    T run(Connection connection) throws Exception;
  }
}
