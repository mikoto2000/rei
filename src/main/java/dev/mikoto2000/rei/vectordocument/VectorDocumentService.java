package dev.mikoto2000.rei.vectordocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.core.configuration.VectorStorePaths;
import tools.jackson.databind.json.JsonMapper;

@Service
public class VectorDocumentService {

  private final SimpleVectorStore vectorStore;
  private final JsonMapper objectMapper;
  private final Clock clock;
  private final Path storeFile;
  private final Path indexFile;

  @Autowired
  public VectorDocumentService(SimpleVectorStore vectorStore, JsonMapper objectMapper) {
    this(vectorStore, objectMapper, Clock.systemUTC(), VectorStorePaths.storeFile(), VectorStorePaths.documentIndexFile());
  }

  VectorDocumentService(SimpleVectorStore vectorStore, JsonMapper objectMapper, Clock clock, Path storeFile, Path indexFile) {
    this.vectorStore = vectorStore;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.storeFile = storeFile;
    this.indexFile = indexFile;
  }

  public List<VectorDocumentEntry> add(List<String> documents) throws IOException {
    List<StoredVectorDocumentEntry> index = loadIndex();
    List<VectorDocumentEntry> added = new ArrayList<>();

    for (String documentPath : documents) {
      String normalizedSource = normalizeSource(documentPath);
      index = new ArrayList<>(deleteBySourceInternal(index, normalizedSource));

      List<Document> chunks = readAndSplit(documentPath);
      String docId = UUID.randomUUID().toString();
      String ingestedAt = OffsetDateTime.now(clock).toString();
      List<String> chunkIds = new ArrayList<>();
      List<Document> documentsToAdd = new ArrayList<>();

      for (int i = 0; i < chunks.size(); i++) {
        Document chunk = chunks.get(i);
        String chunkId = docId + "#" + i;
        chunkIds.add(chunkId);

        Map<String, Object> metadata = new LinkedHashMap<>(chunk.getMetadata());
        metadata.put("docId", docId);
        metadata.put("source", normalizedSource);
        metadata.put("chunkIndex", i);
        metadata.put("ingestedAt", ingestedAt);

        documentsToAdd.add(new Document(chunkId, chunk.getText(), metadata));
      }

      vectorStore.add(documentsToAdd);
      StoredVectorDocumentEntry stored = new StoredVectorDocumentEntry(
          docId,
          normalizedSource,
          documentsToAdd.size(),
          ingestedAt,
          List.copyOf(chunkIds));
      index.add(stored);
      added.add(stored.toEntry());
    }

    saveAll(index);
    return added;
  }

  public List<VectorDocumentEntry> list() throws IOException {
    return loadIndex().stream()
        .map(StoredVectorDocumentEntry::toEntry)
        .toList();
  }

  public List<VectorDocumentSearchResult> search(String query, Integer topK, Double similarityThreshold, String source)
      throws IOException {
    String normalizedSource = source == null || source.isBlank() ? null : normalizeSource(source);
    SearchRequest.Builder builder = SearchRequest.builder()
        .query(query)
        .topK(topK == null ? 5 : topK);
    if (similarityThreshold == null) {
      builder.similarityThresholdAll();
    } else {
      builder.similarityThreshold(similarityThreshold);
    }

    return vectorStore.doSimilaritySearch(builder.build()).stream()
        .filter(document -> normalizedSource == null || normalizedSource.equals(document.getMetadata().get("source")))
        .filter(document -> similarityThreshold != null || (document.getScore() != null && document.getScore() > 0.0d))
        .map(this::toSearchResult)
        .toList();
  }

  public boolean deleteByDocId(String docId) throws IOException {
    List<StoredVectorDocumentEntry> index = loadIndex();
    StoredVectorDocumentEntry target = index.stream()
        .filter(entry -> entry.docId().equals(docId))
        .findFirst()
        .orElse(null);
    if (target == null) {
      return false;
    }

    vectorStore.delete(target.chunkIds());
    index = index.stream()
        .filter(entry -> !entry.docId().equals(docId))
        .toList();
    saveAll(index);
    return true;
  }

  public int deleteBySource(String source) throws IOException {
    String normalizedSource = normalizeSource(source);
    List<StoredVectorDocumentEntry> index = loadIndex();
    int before = index.size();
    index = deleteBySourceInternal(index, normalizedSource);
    int deleted = before - index.size();
    if (deleted > 0) {
      saveAll(index);
    }
    return deleted;
  }

  private List<StoredVectorDocumentEntry> deleteBySourceInternal(List<StoredVectorDocumentEntry> index, String normalizedSource) {
    List<StoredVectorDocumentEntry> matches = index.stream()
        .filter(entry -> entry.source().equals(normalizedSource))
        .toList();
    if (!matches.isEmpty()) {
      List<String> chunkIds = matches.stream()
          .flatMap(entry -> entry.chunkIds().stream())
          .distinct()
          .toList();
      vectorStore.delete(chunkIds);
    }
    return index.stream()
        .filter(entry -> !entry.source().equals(normalizedSource))
        .toList();
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

  private List<StoredVectorDocumentEntry> loadIndex() throws IOException {
    if (!Files.exists(indexFile)) {
      return new ArrayList<>();
    }
    StoredVectorDocumentEntry[] loaded = objectMapper.readValue(indexFile.toFile(), StoredVectorDocumentEntry[].class);
    List<StoredVectorDocumentEntry> entries = new ArrayList<>();
    if (loaded != null) {
      for (StoredVectorDocumentEntry entry : loaded) {
        entries.add(entry);
      }
    }
    return entries;
  }

  private void saveAll(List<StoredVectorDocumentEntry> index) throws IOException {
    createParentDirectories();
    objectMapper.writeValue(indexFile.toFile(), index);
    vectorStore.save(storeFile.toFile());
  }

  private void createParentDirectories() throws IOException {
    LinkedHashSet<Path> parents = new LinkedHashSet<>();
    if (storeFile.getParent() != null) {
      parents.add(storeFile.getParent());
    }
    if (indexFile.getParent() != null) {
      parents.add(indexFile.getParent());
    }
    for (Path parent : parents) {
      Files.createDirectories(parent);
    }
  }
}
