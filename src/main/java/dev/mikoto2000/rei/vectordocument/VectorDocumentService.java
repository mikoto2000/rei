package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
public class VectorDocumentService {

  private final VectorStore vectorStore;
  private final VectorDocumentRepository vectorDocumentRepository;
  private final Clock clock;

  @Autowired
  public VectorDocumentService(VectorStore vectorStore, VectorDocumentRepository vectorDocumentRepository) {
    this(vectorStore, vectorDocumentRepository, Clock.systemUTC());
  }

  VectorDocumentService(VectorStore vectorStore, VectorDocumentRepository vectorDocumentRepository, Clock clock) {
    this.vectorStore = vectorStore;
    this.vectorDocumentRepository = vectorDocumentRepository;
    this.clock = clock;
  }

  public List<VectorDocumentEntry> add(List<String> documents) throws IOException {
    List<VectorDocumentEntry> added = new java.util.ArrayList<>();

    for (String documentPath : documents) {
      String normalizedSource = normalizeSource(documentPath);
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

      added.add(vectorDocumentRepository.replaceBySource(docId, normalizedSource, ingestedAt, documentsToAdd));
    }

    return added;
  }

  public List<VectorDocumentEntry> list() {
    return vectorDocumentRepository.list();
  }

  public List<VectorDocumentSearchResult> search(String query, Integer topK, Double similarityThreshold, String source) {
    SearchRequest.Builder builder = SearchRequest.builder()
        .query(query)
        .topK(topK == null ? 5 : topK);
    if (similarityThreshold == null) {
      builder.similarityThresholdAll();
    } else {
      builder.similarityThreshold(similarityThreshold);
    }
    if (source != null && !source.isBlank()) {
      builder.filterExpression(new FilterExpressionBuilder().eq("source", normalizeSource(source)).build());
    }

    return vectorStore.similaritySearch(builder.build()).stream()
        .map(this::toSearchResult)
        .toList();
  }

  public boolean deleteByDocId(String docId) {
    return vectorDocumentRepository.deleteByDocId(docId);
  }

  public int deleteBySource(String source) {
    return vectorDocumentRepository.deleteBySource(normalizeSource(source));
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
        chunkIndex instanceof Number number ? number.intValue() : -1,
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
