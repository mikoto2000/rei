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

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class SqliteVectorStore implements VectorStore {

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
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var documentStatement = connection.prepareStatement("""
          INSERT OR REPLACE INTO documents (doc_id, source)
          VALUES (?, ?)
          """);
          var chunkStatement = connection.prepareStatement("""
              INSERT OR REPLACE INTO document_chunks
                (chunk_id, doc_id, chunk_index, text, metadata_json, embedding_json, embedding_dim)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              """)) {
        for (Document document : documents) {
          Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
          String docId = stringValue(metadata.get("docId"), document.getId());
          String source = stringValue(metadata.get("source"), "");
          int chunkIndex = intValue(metadata.get("chunkIndex"));
          float[] embedding = embeddingModel.embed(document);

          documentStatement.setString(1, docId);
          documentStatement.setString(2, source);
          documentStatement.addBatch();

          chunkStatement.setString(1, document.getId());
          chunkStatement.setString(2, docId);
          chunkStatement.setInt(3, chunkIndex);
          chunkStatement.setString(4, document.getText());
          chunkStatement.setString(5, writeJson(metadata));
          chunkStatement.setString(6, writeJson(embedding));
          chunkStatement.setInt(7, embedding.length);
          chunkStatement.addBatch();
        }
        documentStatement.executeBatch();
        chunkStatement.executeBatch();
        connection.commit();
      } catch (Exception e) {
        connection.rollback();
        throw new IllegalStateException("ベクトル文書の登録に失敗しました", e);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("SQLite への接続に失敗しました", e);
    }
  }

  @Override
  public void delete(List<String> ids) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var deleteChunks = connection.prepareStatement("DELETE FROM document_chunks WHERE chunk_id = ?");
          var deleteOrphans = connection.prepareStatement("""
              DELETE FROM documents
              WHERE doc_id NOT IN (SELECT DISTINCT doc_id FROM document_chunks)
              """)) {
        for (String id : ids) {
          deleteChunks.setString(1, id);
          deleteChunks.addBatch();
        }
        deleteChunks.executeBatch();
        deleteOrphans.executeUpdate();
        connection.commit();
      } catch (Exception e) {
        connection.rollback();
        throw new IllegalStateException("ベクトル文書の削除に失敗しました", e);
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("SQLite への接続に失敗しました", e);
    }
  }

  @Override
  public void delete(Filter.Expression filterExpression) {
    throw new UnsupportedOperationException("filter による削除は未対応です");
  }

  @Override
  public List<Document> similaritySearch(SearchRequest request) {
    float[] queryEmbedding = embeddingModel.embed(new Document(request.getQuery()));
    List<ScoredDocument> scoredDocuments = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        var statement = connection.prepareStatement("""
            SELECT chunk_id, text, metadata_json, embedding_json
            FROM document_chunks
            ORDER BY chunk_id ASC
            """)) {
      try (var rs = statement.executeQuery()) {
        while (rs.next()) {
          float[] embedding = readEmbedding(rs.getString("embedding_json"));
          if (embedding.length != queryEmbedding.length) {
            throw new IllegalStateException("embedding 次元が一致しません");
          }
          double score = cosineSimilarity(queryEmbedding, embedding);
          if (score > 0.0d && score >= request.getSimilarityThreshold()) {
            Map<String, Object> metadata = readMetadata(rs.getString("metadata_json"));
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
  public <T> Optional<T> getNativeClient() {
    return Optional.of((T) dataSource);
  }

  private void initializeSchema() {
    try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS documents (
            doc_id TEXT PRIMARY KEY,
            source TEXT NOT NULL
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
    return value instanceof Number number ? number.intValue() : 0;
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

  private record ScoredDocument(Document document, double score) {
  }
}
