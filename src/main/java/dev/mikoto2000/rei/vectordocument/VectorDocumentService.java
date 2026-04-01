package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
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
  private final VectorDocumentProperties properties;

  @Autowired
  public VectorDocumentService(
      VectorStore vectorStore,
      VectorDocumentRepository vectorDocumentRepository,
      VectorDocumentProperties properties) {
    this(vectorStore, vectorDocumentRepository, Clock.systemUTC(), properties);
  }

  VectorDocumentService(
      VectorStore vectorStore,
      VectorDocumentRepository vectorDocumentRepository,
      Clock clock,
      VectorDocumentProperties properties) {
    this.vectorStore = vectorStore;
    this.vectorDocumentRepository = vectorDocumentRepository;
    this.clock = clock;
    this.properties = properties;
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
    int requestedTopK = topK == null ? 5 : topK;
    SearchRequest.Builder builder = SearchRequest.builder()
        .query(query)
        .topK(expandedTopK(requestedTopK));
    if (similarityThreshold == null) {
      builder.similarityThresholdAll();
    } else {
      builder.similarityThreshold(similarityThreshold);
    }
    if (source != null && !source.isBlank()) {
      builder.filterExpression(new FilterExpressionBuilder().eq("source", normalizeSource(source)).build());
    }

    return vectorStore.similaritySearch(builder.build()).stream()
        .collect(java.util.stream.Collectors.toMap(
            document -> asString(document.getMetadata().get("docId")),
            document -> document,
            this::pickHigherScoredDocument,
            LinkedHashMap::new))
        .values().stream()
        .sorted(Comparator.comparing(Document::getScore).reversed().thenComparing(Document::getId))
        .limit(requestedTopK)
        .map(document -> toSearchResult(query, document))
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
        .withChunkSize(properties.chunkSize())
        .build();
    return textSplitter.apply(documentReader.get());
  }

  private VectorDocumentSearchResult toSearchResult(String query, Document document) {
    Map<String, Object> metadata = document.getMetadata();
    Object chunkIndex = metadata.get("chunkIndex");
    return new VectorDocumentSearchResult(
        asString(metadata.get("docId")),
        asString(metadata.get("source")),
        chunkIndex instanceof Number number ? number.intValue() : -1,
        document.getScore(),
        snippet(query, document.getText()));
  }

  private int expandedTopK(int requestedTopK) {
    return Math.max(requestedTopK, requestedTopK * 4);
  }

  private Document pickHigherScoredDocument(Document left, Document right) {
    double leftScore = left.getScore() == null ? Double.NEGATIVE_INFINITY : left.getScore();
    double rightScore = right.getScore() == null ? Double.NEGATIVE_INFINITY : right.getScore();
    if (leftScore != rightScore) {
      return leftScore >= rightScore ? left : right;
    }
    return left.getId().compareTo(right.getId()) <= 0 ? left : right;
  }

  private String snippet(String query, String text) {
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    if (normalized.isEmpty()) {
      return normalized;
    }
    int maxLength = 120;
    if (normalized.length() <= maxLength) {
      return normalized;
    }

    int start = snippetStart(query, normalized, maxLength);
    int end = Math.min(normalized.length(), start + maxLength);

    String prefix = start > 0 ? "..." : "";
    String suffix = end < normalized.length() ? "..." : "";
    return prefix + normalized.substring(start, end).trim() + suffix;
  }

  private int snippetStart(String query, String normalizedText, int maxLength) {
    String normalizedQuery = query == null ? "" : query.replaceAll("\\s+", " ").trim().toLowerCase();
    if (!normalizedQuery.isEmpty()) {
      int queryIndex = normalizedText.toLowerCase().indexOf(normalizedQuery);
      if (queryIndex >= 0) {
        return Math.max(0, queryIndex - 24);
      }

      for (String token : normalizedQuery.split(" ")) {
        if (token.isBlank()) {
          continue;
        }
        int tokenIndex = normalizedText.toLowerCase().indexOf(token);
        if (tokenIndex >= 0) {
          return Math.max(0, tokenIndex - 24);
        }
      }
    }

    return 0;
  }

  private String asString(Object value) {
    return value == null ? "" : value.toString();
  }

  private String normalizeSource(String source) {
    return Path.of(source).toAbsolutePath().normalize().toString();
  }
}
