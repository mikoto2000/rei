package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class VectorDocumentService {

  private final JdbcClient jdbcClient;
  private final VectorStore vectorStore;
  private final Clock clock;

  @Autowired
  public VectorDocumentService(DataSource dataSource, VectorStore vectorStore) {
    this(dataSource, vectorStore, Clock.systemUTC());
  }

  VectorDocumentService(DataSource dataSource, VectorStore vectorStore, Clock clock) {
    this.jdbcClient = JdbcClient.create(dataSource);
    this.vectorStore = vectorStore;
    this.clock = clock;
  }

  public List<VectorDocumentEntry> add(List<String> documents) throws IOException {
    List<VectorDocumentEntry> added = new java.util.ArrayList<>();

    for (String documentPath : documents) {
      String normalizedSource = normalizeSource(documentPath);
      deleteBySource(normalizedSource);

      List<Document> chunks = readAndSplit(documentPath);
      String docId = UUID.randomUUID().toString();
      String ingestedAt = OffsetDateTime.now(clock).toString();
      List<Document> documentsToAdd = new java.util.ArrayList<>();

      for (int i = 0; i < chunks.size(); i++) {
        Document chunk = chunks.get(i);
        Map<String, Object> metadata = new LinkedHashMap<>(chunk.getMetadata());
        metadata.put("docId", docId);
        metadata.put("source", normalizedSource);
        metadata.put("chunkIndex", i);
        metadata.put("ingestedAt", ingestedAt);
        documentsToAdd.add(new Document(docId + "#" + i, chunk.getText(), metadata));
      }

      vectorStore.add(documentsToAdd);
      added.add(new VectorDocumentEntry(docId, normalizedSource, documentsToAdd.size(), ingestedAt));
    }

    return added;
  }

  public List<VectorDocumentEntry> list() {
    return jdbcClient.sql("""
        SELECT d.doc_id, d.source, COUNT(c.chunk_id) AS chunk_count, d.ingested_at
        FROM documents d
        JOIN document_chunks c ON c.doc_id = d.doc_id
        GROUP BY d.doc_id, d.source, d.ingested_at
        ORDER BY d.source ASC, d.doc_id ASC
        """)
        .query((rs, rowNum) -> new VectorDocumentEntry(
            rs.getString("doc_id"),
            rs.getString("source"),
            rs.getInt("chunk_count"),
            rs.getString("ingested_at")))
        .list();
  }

  public List<VectorDocumentSearchResult> search(String query, Integer topK, Double similarityThreshold, String source) {
    String normalizedSource = source == null || source.isBlank() ? null : normalizeSource(source);
    SearchRequest.Builder builder = SearchRequest.builder()
        .query(query)
        .topK(topK == null ? 5 : topK);
    if (similarityThreshold == null) {
      builder.similarityThresholdAll();
    } else {
      builder.similarityThreshold(similarityThreshold);
    }

    return vectorStore.similaritySearch(builder.build()).stream()
        .filter(document -> normalizedSource == null || normalizedSource.equals(document.getMetadata().get("source")))
        .map(this::toSearchResult)
        .toList();
  }

  public boolean deleteByDocId(String docId) {
    List<String> chunkIds = jdbcClient.sql("""
        SELECT chunk_id
        FROM document_chunks
        WHERE doc_id = ?
        ORDER BY chunk_index ASC
        """)
        .param(docId)
        .query(String.class)
        .list();
    if (chunkIds.isEmpty()) {
      return false;
    }
    vectorStore.delete(chunkIds);
    return true;
  }

  public int deleteBySource(String source) {
    String normalizedSource = normalizeSource(source);
    List<String> docIds = jdbcClient.sql("""
        SELECT doc_id
        FROM documents
        WHERE source = ?
        ORDER BY doc_id ASC
        """)
        .param(normalizedSource)
        .query(String.class)
        .list();
    if (docIds.isEmpty()) {
      return 0;
    }

    List<String> chunkIds = jdbcClient.sql("""
        SELECT c.chunk_id
        FROM document_chunks c
        JOIN documents d ON d.doc_id = c.doc_id
        WHERE d.source = ?
        ORDER BY c.chunk_index ASC
        """)
        .param(normalizedSource)
        .query(String.class)
        .list();
    vectorStore.delete(chunkIds);
    return docIds.size();
  }

  private List<Document> readAndSplit(String documentPath) {
    TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(documentPath));
    TextSplitter textSplitter = TokenTextSplitter.builder()
        .withChunkSize(500)
        .build();
    return textSplitter.apply(documentReader.get());
  }

  private VectorDocumentSearchResult toSearchResult(Document document) {
    Map<String, Object> metadata = document.getMetadata();
    Object chunkIndex = metadata.get("chunkIndex");
    return new VectorDocumentSearchResult(
        asString(metadata.get("docId")),
        asString(metadata.get("source")),
        chunkIndex instanceof Number number ? number.intValue() : 0,
        document.getScore(),
        snippet(document.getText()));
  }

  private String snippet(String text) {
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
  }

  private String asString(Object value) {
    return value == null ? "" : value.toString();
  }

  private String normalizeSource(String source) {
    return Path.of(source).toAbsolutePath().normalize().toString();
  }
}
