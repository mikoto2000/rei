package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
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
        .collect(java.util.stream.Collectors.groupingBy(
            document -> asString(document.getMetadata().get("docId")),
            LinkedHashMap::new,
            java.util.stream.Collectors.toList()))
        .values().stream()
        .map(documents -> aggregateResult(query, documents))
        .sorted(Comparator.comparing(AggregatedSearchResult::score).reversed().thenComparing(AggregatedSearchResult::docId))
        .limit(requestedTopK)
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
    Path path = Path.of(documentPath);
    String fileType = fileType(path);
    String title = title(path);
    TextSplitter textSplitter = splitterFor(fileType);

    if (isMarkdown(fileType)) {
      return textSplitter.apply(markdownSections(path, title, fileType));
    }

    TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(documentPath));
    return textSplitter.apply(documentReader.get().stream()
        .map(document -> enrichMetadata(document, title, fileType, null))
        .toList());
  }

  private VectorDocumentSearchResult toSearchResult(AggregatedSearchResult result) {
    return new VectorDocumentSearchResult(
        result.docId(),
        result.source(),
        result.chunkIndex(),
        result.score(),
        snippet(result.query(), result.mergedText()));
  }

  private int expandedTopK(int requestedTopK) {
    return Math.max(requestedTopK, requestedTopK * 4);
  }

  private AggregatedSearchResult aggregateResult(String query, List<Document> documents) {
    List<Document> sortedByChunkIndex = documents.stream()
        .sorted(Comparator.comparingInt(this::chunkIndex))
        .toList();
    Document best = sortedByChunkIndex.stream()
        .max(Comparator.comparing(this::documentScore).thenComparing(Document::getId))
        .orElseThrow();

    double score = compositeScore(query, sortedByChunkIndex);
    return new AggregatedSearchResult(
        query,
        asString(best.getMetadata().get("docId")),
        asString(best.getMetadata().get("source")),
        chunkIndex(best),
        score,
        mergeAdjacentChunks(sortedByChunkIndex, chunkIndex(best)));
  }

  private String snippet(String query, String text) {
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    if (normalized.isEmpty()) {
      return normalized;
    }
    String bestSentence = bestMatchingSentence(query, normalized);
    if (bestSentence != null && !bestSentence.isBlank()) {
      return trimSnippetAroundQuery(query, bestSentence.trim(), 120);
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

  private String bestMatchingSentence(String query, String normalizedText) {
    List<String> sentences = splitSentences(normalizedText);
    if (sentences.isEmpty()) {
      return normalizedText;
    }
    return sentences.stream()
        .max(Comparator
            .comparingDouble((String sentence) -> sentenceLexicalScore(query, sentence))
            .thenComparingInt((String sentence) -> sentence.length()))
        .orElse(normalizedText);
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

  private List<String> splitSentences(String text) {
    return java.util.Arrays.stream(text.split("(?<=[。.!?])\\s+"))
        .map(String::trim)
        .filter(sentence -> !sentence.isBlank())
        .toList();
  }

  private double sentenceLexicalScore(String query, String sentence) {
    String normalizedQuery = query == null ? "" : query.replaceAll("\\s+", " ").trim().toLowerCase();
    if (normalizedQuery.isBlank()) {
      return 0.0d;
    }
    String lowerSentence = sentence.toLowerCase();
    if (lowerSentence.contains(normalizedQuery)) {
      return 10.0d;
    }
    double score = 0.0d;
    for (String token : normalizedQuery.split(" ")) {
      if (!token.isBlank() && lowerSentence.contains(token)) {
        score += 1.0d;
      }
    }
    return score;
  }

  private String trimSnippetAroundQuery(String query, String text, int maxLength) {
    String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    int start = snippetStart(query, normalized, maxLength);
    int end = Math.min(normalized.length(), start + maxLength);
    String prefix = start > 0 ? "..." : "";
    String suffix = end < normalized.length() ? "..." : "";
    return prefix + normalized.substring(start, end).trim() + suffix;
  }

  private double compositeScore(String query, List<Document> documents) {
    List<Double> topScores = documents.stream()
        .map(this::documentScore)
        .sorted(Comparator.reverseOrder())
        .limit(2)
        .toList();
    double best = topScores.isEmpty() ? 0.0d : topScores.getFirst();
    double second = topScores.size() > 1 ? topScores.get(1) : 0.0d;
    double lexicalCoverage = lexicalCoverage(query, mergeAdjacentChunks(documents, chunkIndex(documents.getFirst())));
    return best + second + (lexicalCoverage * 0.1d);
  }

  private double lexicalCoverage(String query, String text) {
    String normalizedQuery = query == null ? "" : query.replaceAll("\\s+", " ").trim().toLowerCase();
    if (normalizedQuery.isBlank() || text == null || text.isBlank()) {
      return 0.0d;
    }
    String lowerText = text.toLowerCase();
    List<String> tokens = java.util.Arrays.stream(normalizedQuery.split(" "))
        .filter(token -> !token.isBlank())
        .distinct()
        .toList();
    if (tokens.isEmpty()) {
      return 0.0d;
    }
    long matches = tokens.stream().filter(lowerText::contains).count();
    return (double) matches / (double) tokens.size();
  }

  private String mergeAdjacentChunks(List<Document> documents, int centerChunkIndex) {
    List<String> parts = new ArrayList<>();
    for (Document document : documents) {
      int chunkIndex = chunkIndex(document);
      if (Math.abs(chunkIndex - centerChunkIndex) <= 1) {
        parts.add(document.getText());
      }
    }
    return String.join(" ", parts).replaceAll("\\s+", " ").trim();
  }

  private int chunkIndex(Document document) {
    Object chunkIndex = document.getMetadata().get("chunkIndex");
    return chunkIndex instanceof Number number ? number.intValue() : -1;
  }

  private double documentScore(Document document) {
    return document.getScore() == null ? Double.NEGATIVE_INFINITY : document.getScore();
  }

  private String asString(Object value) {
    return value == null ? "" : value.toString();
  }

  private String normalizeSource(String source) {
    return Path.of(source).toAbsolutePath().normalize().toString();
  }

  private TextSplitter splitterFor(String fileType) {
    int chunkSize = properties.chunkSize();
    int chunkOverlap = properties.chunkOverlap();

    if (isMarkdown(fileType)) {
      chunkSize = Math.max(32, (int) Math.round(properties.chunkSize() * 0.75d));
      chunkOverlap = Math.min(chunkSize - 1, Math.max(chunkOverlap, chunkSize / 3));
    } else if ("pdf".equals(fileType)) {
      chunkSize = Math.max(chunkSize + 1, (int) Math.round(properties.chunkSize() * 1.5d));
      chunkOverlap = Math.min(chunkSize - 1, Math.max(0, properties.chunkOverlap() / 2));
    }

    return new OverlappingTokenTextSplitter(chunkSize, chunkOverlap);
  }

  private List<Document> markdownSections(Path path, String title, String fileType) {
    try {
      List<Document> sections = new ArrayList<>();
      String currentHeading = title;
      StringBuilder body = new StringBuilder();
      for (String line : Files.readAllLines(path)) {
        if (line.stripLeading().startsWith("#")) {
          if (body.length() > 0) {
            sections.add(new Document(body.toString().trim(), Map.of(
                "title", title,
                "fileType", fileType,
                "sectionHeading", currentHeading)));
            body = new StringBuilder();
          }
          currentHeading = line.replaceFirst("^#+\\s*", "").trim();
          continue;
        }
        body.append(line).append(System.lineSeparator());
      }
      if (body.length() > 0) {
        sections.add(new Document(body.toString().trim(), Map.of(
            "title", title,
            "fileType", fileType,
            "sectionHeading", currentHeading)));
      }
      if (sections.isEmpty()) {
        sections.add(new Document("", Map.of("title", title, "fileType", fileType, "sectionHeading", currentHeading)));
      }
      return sections;
    } catch (IOException e) {
      throw new IllegalStateException("Markdown 文書の読み込みに失敗しました: " + path, e);
    }
  }

  private Document enrichMetadata(Document document, String title, String fileType, String sectionHeading) {
    Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
    metadata.put("title", title);
    metadata.put("fileType", fileType);
    if (sectionHeading != null && !sectionHeading.isBlank()) {
      metadata.put("sectionHeading", sectionHeading);
    }
    return new Document(document.getId(), document.getText(), metadata);
  }

  private boolean isMarkdown(String fileType) {
    return "md".equals(fileType) || "markdown".equals(fileType);
  }

  private String fileType(Path path) {
    String fileName = path.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return "unknown";
    }
    return fileName.substring(dot + 1).toLowerCase();
  }

  private String title(Path path) {
    String fileName = path.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private record AggregatedSearchResult(
      String query,
      String docId,
      String source,
      int chunkIndex,
      double score,
      String mergedText) {
  }
}
