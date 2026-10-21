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

/** ベクトル文書のユースケースを実現する。 */
@Service
@org.springframework.boot.context.properties.EnableConfigurationProperties(
    dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties.class)
public class VectorDocumentService {

  /** ベクトル保存先。 */
  private final VectorStore vectorStore;

  /** ベクトル文書保存と検索の窓口。 */
  private final VectorDocumentRepository vectorDocumentRepository;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** この機能の動作設定。 */
  private final VectorDocumentProperties properties;

  /** 再評価サービス。 */
  private CandidateReranker rerankService;

  /** 関連情報の取得。 */
  private dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties retrieval =
      new dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties(false, 40, 60);

  /** 検索対象文書の管理で使用する関連情報の取得を設定する。 */
  @Autowired
  void setRetrieval(dev.mikoto2000.rei.vectorstore.HybridRetrievalProperties retrieval) {
    this.retrieval = retrieval;
  }

  /** 検索対象文書の管理で使用する再評価サービスを設定する。 */
  @Autowired
  void setRerankService(CandidateReranker rerankService) {
    this.rerankService = rerankService;
  }

  /** 検索対象文書の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public VectorDocumentService(
      VectorStore vectorStore,
      VectorDocumentRepository vectorDocumentRepository,
      VectorDocumentProperties properties) {
    this(vectorStore, vectorDocumentRepository, Clock.systemUTC(), properties);
  }

  /** 指定された値や利用先を使って、VectorDocumentServiceを初期化する。 */
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

  /** 新たな対象を登録し、後から利用できるようにする。 */
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

      added.add(
          vectorDocumentRepository.replaceBySource(
              docId, normalizedSource, ingestedAt, documentsToAdd));
    }

    return added;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<VectorDocumentEntry> list() {
    return vectorDocumentRepository.list();
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<VectorDocumentSearchResult> search(
      String query, Integer topK, Double similarityThreshold, String source) {
    int requestedTopK = topK == null ? 5 : topK;
    if (retrieval.enabled() && (requestedTopK < 1 || requestedTopK > 64))
      throw new IllegalArgumentException("RRF document topK must be 1..64");
    SearchRequest.Builder builder =
        SearchRequest.builder().query(query).topK(expandedTopK(requestedTopK));
    if (similarityThreshold == null) {
      builder.similarityThresholdAll();
    } else {
      builder.similarityThreshold(similarityThreshold);
    }
    if (source != null && !source.isBlank()) {
      builder.filterExpression(
          new FilterExpressionBuilder().eq("source", normalizeSource(source)).build());
    }

    var request = builder.build();
    List<Document> chunks;
    if (retrieval.enabled()) {
      if (!(vectorStore instanceof dev.mikoto2000.rei.vectorstore.RetrievalCandidates backend))
        throw new IllegalStateException(
            "Vector store does not support independent retrieval candidates");
      chunks =
          new dev.mikoto2000.rei.vectorstore.HybridRetriever(backend, retrieval).search(request);
    } else chunks = vectorStore.similaritySearch(request);
    List<AggregatedSearchResult> candidates =
        chunks.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    document -> asString(document.getMetadata().get("docId")),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.toList()))
            .values()
            .stream()
            .map(documents -> aggregateResult(query, documents))
            .sorted(
                Comparator.comparing(AggregatedSearchResult::score)
                    .reversed()
                    .thenComparing(AggregatedSearchResult::docId))
            .toList();
    if (rerankService != null) {
      candidates = rerankService.rerank(query, candidates, AggregatedSearchResult::mergedText);
    }
    return candidates.stream().limit(requestedTopK).map(this::toSearchResult).toList();
  }

  /** 対象を管理から外すため、別文書の識別子を削除する。 */
  public boolean deleteByDocId(String docId) {
    return vectorDocumentRepository.deleteByDocId(docId);
  }

  /** 対象を管理から外すため、別取得元を削除する。 */
  public int deleteBySource(String source) {
    return vectorDocumentRepository.deleteBySource(normalizeSource(source));
  }

  /** 必要な内容を参照できるよう、および分割を読み取る。 */
  private List<Document> readAndSplit(String documentPath) {
    Path path = Path.of(documentPath);
    String fileType = fileType(path);
    String title = title(path);
    TextSplitter textSplitter = splitterFor(fileType);

    if (isMarkdown(fileType)) {
      return textSplitter.apply(markdownSections(path, title, fileType));
    }

    TikaDocumentReader documentReader =
        new TikaDocumentReader(new FileSystemResource(documentPath));
    return textSplitter.apply(
        documentReader.get().stream()
            .map(document -> enrichMetadata(document, title, fileType, null))
            .toList());
  }

  /** 利用先へ渡せるよう、検索結果の形式に変換する。 */
  private VectorDocumentSearchResult toSearchResult(AggregatedSearchResult result) {
    return new VectorDocumentSearchResult(
        result.docId(),
        result.source(),
        result.chunkIndex(),
        result.score(),
        snippet(result.query(), result.mergedText()));
  }

  /** 検索対象文書の管理で用いるexpanded上位件数を返す。 */
  private int expandedTopK(int requestedTopK) {
    return Math.max(requestedTopK, requestedTopK * 4);
  }

  /** 全体の傾向や結果を把握できるよう、結果を集計する。 */
  private AggregatedSearchResult aggregateResult(String query, List<Document> documents) {
    List<Document> sortedByChunkIndex =
        documents.stream().sorted(Comparator.comparingInt(this::chunkIndex)).toList();
    Document best =
        sortedByChunkIndex.stream()
            .max(Comparator.comparing(this::documentScore).thenComparing(Document::getId))
            .orElseThrow();

    double score =
        retrieval.enabled() ? documentScore(best) : compositeScore(query, sortedByChunkIndex);
    return new AggregatedSearchResult(
        query,
        asString(best.getMetadata().get("docId")),
        asString(best.getMetadata().get("source")),
        chunkIndex(best),
        score,
        mergeAdjacentChunks(sortedByChunkIndex, chunkIndex(best)));
  }

  /** 検索対象文書の管理で用いる抜粋を返す。 */
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

  /** 検索対象文書の管理で用いるbestMatching文を返す。 */
  private String bestMatchingSentence(String query, String normalizedText) {
    List<String> sentences = splitSentences(normalizedText);
    if (sentences.isEmpty()) {
      return normalizedText;
    }
    return sentences.stream()
        .max(
            Comparator.comparingDouble((String sentence) -> sentenceLexicalScore(query, sentence))
                .thenComparingInt((String sentence) -> sentence.length()))
        .orElse(normalizedText);
  }

  /** 検索対象文書の管理で用いる抜粋開始を返す。 */
  private int snippetStart(String query, String normalizedText, int maxLength) {
    String normalizedQuery =
        query == null ? "" : query.replaceAll("\\s+", " ").trim().toLowerCase();
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

  /** 検索対象文書の管理で用いる分割文一覧を返す。 */
  private List<String> splitSentences(String text) {
    return java.util.Arrays.stream(text.split("(?<=[。.!?])\\s+"))
        .map(String::trim)
        .filter(sentence -> !sentence.isBlank())
        .toList();
  }

  /** 検索対象文書の管理で用いる文語句による評価値を返す。 */
  private double sentenceLexicalScore(String query, String sentence) {
    String normalizedQuery =
        query == null ? "" : query.replaceAll("\\s+", " ").trim().toLowerCase();
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

  /** 検索対象文書の管理で用いる余白の除去抜粋Around検索条件を返す。 */
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

  /** 検索対象文書の管理で用いるcomposite評価値を返す。 */
  private double compositeScore(String query, List<Document> documents) {
    List<Double> topScores =
        documents.stream()
            .map(this::documentScore)
            .sorted(Comparator.reverseOrder())
            .limit(2)
            .toList();
    double best = topScores.isEmpty() ? 0.0d : topScores.getFirst();
    double second = topScores.size() > 1 ? topScores.get(1) : 0.0d;
    double lexicalCoverage =
        lexicalCoverage(query, mergeAdjacentChunks(documents, chunkIndex(documents.getFirst())));
    return best + second + (lexicalCoverage * 0.1d);
  }

  /** 検索対象文書の管理で用いる語句による対象範囲を返す。 */
  private double lexicalCoverage(String query, String text) {
    String normalizedQuery =
        query == null ? "" : query.replaceAll("\\s+", " ").trim().toLowerCase();
    if (normalizedQuery.isBlank() || text == null || text.isBlank()) {
      return 0.0d;
    }
    String lowerText = text.toLowerCase();
    List<String> tokens =
        java.util.Arrays.stream(normalizedQuery.split(" "))
            .filter(token -> !token.isBlank())
            .distinct()
            .toList();
    if (tokens.isEmpty()) {
      return 0.0d;
    }
    long matches = tokens.stream().filter(lowerText::contains).count();
    return (double) matches / (double) tokens.size();
  }

  /** 関連する情報をまとめて扱えるよう、Adjacent文書の分割一覧を統合する。 */
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

  /** 検索対象文書の管理で用いる文書の分割単位位置を返す。 */
  private int chunkIndex(Document document) {
    Object chunkIndex = document.getMetadata().get("chunkIndex");
    return chunkIndex instanceof Number number ? number.intValue() : -1;
  }

  /** 検索対象文書の管理で用いる文書評価値を返す。 */
  private double documentScore(Document document) {
    return document.getScore() == null ? Double.NEGATIVE_INFINITY : document.getScore();
  }

  /** 後続の処理が参照できるよう、文字列表現として扱う。 */
  private String asString(Object value) {
    return value == null ? "" : value.toString();
  }

  /** 同じ意味の指定を共通に扱えるよう、取得元の表記をそろえる。 */
  private String normalizeSource(String source) {
    return Path.of(source).toAbsolutePath().normalize().toString();
  }

  /** 検索対象文書の管理で用いるsplitter対象を返す。 */
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

  /** 検索対象文書の管理で用いるMarkdown節一覧を返す。 */
  private List<Document> markdownSections(Path path, String title, String fileType) {
    try {
      List<Document> sections = new ArrayList<>();
      String currentHeading = title;
      StringBuilder body = new StringBuilder();
      for (String line : Files.readAllLines(path)) {
        if (line.stripLeading().startsWith("#")) {
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (body.length() > 0) {
            sections.add(
                new Document(
                    body.toString().trim(),
                    Map.of(
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
        sections.add(
            new Document(
                body.toString().trim(),
                Map.of(
                    "title", title,
                    "fileType", fileType,
                    "sectionHeading", currentHeading)));
      }
      if (sections.isEmpty()) {
        sections.add(
            new Document(
                "",
                Map.of("title", title, "fileType", fileType, "sectionHeading", currentHeading)));
      }
      return sections;
    } catch (IOException e) {
      throw new IllegalStateException("Markdown 文書の読み込みに失敗しました: " + path, e);
    }
  }

  /** 検索対象文書の管理で用いるenrich付随情報を返す。 */
  private Document enrichMetadata(
      Document document, String title, String fileType, String sectionHeading) {
    Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
    metadata.put("title", title);
    metadata.put("fileType", fileType);
    if (sectionHeading != null && !sectionHeading.isBlank()) {
      metadata.put("sectionHeading", sectionHeading);
    }
    return new Document(document.getId(), document.getText(), metadata);
  }

  /** 検索対象文書の管理において、Markdownかどうかを判定する。 */
  private boolean isMarkdown(String fileType) {
    return "md".equals(fileType) || "markdown".equals(fileType);
  }

  /** 検索対象文書の管理で用いるファイル種別を返す。 */
  private String fileType(Path path) {
    String fileName = path.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return "unknown";
    }
    return fileName.substring(dot + 1).toLowerCase();
  }

  /** 検索対象文書の管理で用いるタイトルを返す。 */
  private String title(Path path) {
    String fileName = path.getFileName().toString();
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  /**
   * 検索対象文書の管理で受け渡す集計済み検索結果を表す。
   *
   * @param query 検索条件
   * @param docId 文書の識別子
   * @param source 取得元
   * @param chunkIndex 文書の分割単位位置
   * @param score 評価値
   * @param mergedText 統合済みテキスト
   */
  private record AggregatedSearchResult(
      String query, String docId, String source, int chunkIndex, double score, String mergedText) {}
}
