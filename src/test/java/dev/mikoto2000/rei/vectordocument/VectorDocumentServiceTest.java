package dev.mikoto2000.rei.vectordocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import dev.mikoto2000.rei.vectorstore.SqliteVectorStore;

class VectorDocumentServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void addListSearchAndDeleteDocuments() throws IOException {
    Path springDoc = Files.writeString(tempDir.resolve("spring-ai.txt"), "Spring AI guide for building AI assistants.");
    Path weatherDoc = Files.writeString(tempDir.resolve("weather.txt"), "Weather forecast memo for Ibaraki tomorrow.");

    VectorDocumentService service = newService();

    List<VectorDocumentEntry> added = service.add(List.of(springDoc.toString(), weatherDoc.toString()));

    assertEquals(2, added.size());
    assertEquals(2, service.list().size());
    assertTrue(service.list().stream().anyMatch(entry -> entry.source().endsWith("spring-ai.txt")));
    assertTrue(service.list().stream().allMatch(entry -> entry.chunkCount() > 0));

    List<VectorDocumentSearchResult> results = service.search("spring ai", 3, null, null);

    assertFalse(results.isEmpty());
    assertEquals("Spring AI guide for building AI assistants.", results.getFirst().snippet());
    assertTrue(results.getFirst().source().endsWith("spring-ai.txt"));

    String springDocId = added.stream()
        .filter(entry -> entry.source().endsWith("spring-ai.txt"))
        .findFirst()
        .orElseThrow()
        .docId();

    assertTrue(service.deleteByDocId(springDocId));
    assertEquals(1, service.list().size());
    assertTrue(service.search("spring ai", 3, null, null).isEmpty());
  }

  @Test
  void addReplacesExistingDocumentWithSameSource() throws IOException {
    Path springDoc = tempDir.resolve("spring-ai.txt");
    Files.writeString(springDoc, "first version about spring ai");

    VectorDocumentService service = newService();
    VectorDocumentEntry first = service.add(List.of(springDoc.toString())).getFirst();

    Files.writeString(springDoc, "second version about spring ai and tools");
    VectorDocumentEntry second = service.add(List.of(springDoc.toString())).getFirst();

    assertEquals(1, service.list().size());
    assertFalse(first.docId().equals(second.docId()));
    List<VectorDocumentSearchResult> results = service.search("tools", 3, null, null);
    assertEquals(1, results.size());
    assertEquals(second.docId(), results.getFirst().docId());
  }

  @Test
  void searchCanFilterBySource() throws IOException {
    Path springDoc = Files.writeString(tempDir.resolve("spring-ai.txt"), "Spring AI guide for building AI assistants.");
    Path weatherDoc = Files.writeString(tempDir.resolve("weather.txt"), "Spring weather outlook for Ibaraki.");

    VectorDocumentService service = newService();
    service.add(List.of(springDoc.toString(), weatherDoc.toString()));

    List<VectorDocumentSearchResult> results = service.search("spring", 5, null, springDoc.toAbsolutePath().normalize().toString());

    assertEquals(1, results.size());
    assertTrue(results.getFirst().source().endsWith("spring-ai.txt"));
  }

  @Test
  void deleteBySourceRemovesMatchingDocument() throws IOException {
    Path springDoc = Files.writeString(tempDir.resolve("spring-ai.txt"), "Spring AI guide for building AI assistants.");
    Path weatherDoc = Files.writeString(tempDir.resolve("weather.txt"), "Weather forecast memo for Ibaraki tomorrow.");

    VectorDocumentService service = newService();
    service.add(List.of(springDoc.toString(), weatherDoc.toString()));

    int deleted = service.deleteBySource(weatherDoc.toAbsolutePath().normalize().toString());

    assertEquals(1, deleted);
    assertEquals(1, service.list().size());
    assertTrue(service.list().getFirst().source().endsWith("spring-ai.txt"));
  }

  private VectorDocumentService newService() {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("memory.db"));
    SqliteVectorStore vectorStore = new SqliteVectorStore(dataSource, new FakeEmbeddingModel(), new tools.jackson.databind.json.JsonMapper());
    return new VectorDocumentService(
        dataSource,
        vectorStore,
        Clock.fixed(Instant.parse("2026-03-28T00:00:00Z"), ZoneOffset.UTC));
  }

  private static final class FakeEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<Embedding> embeddings = java.util.stream.IntStream.range(0, request.getInstructions().size())
          .mapToObj(i -> new Embedding(embedText(request.getInstructions().get(i)), i))
          .toList();
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      return embedText(document.getText());
    }

    @Override
    public int dimensions() {
      return 4;
    }

    private float[] embedText(String text) {
      String value = text == null ? "" : text.toLowerCase();
      return new float[] {
          score(value, "spring"),
          score(value, "ai"),
          score(value, "weather"),
          score(value, "tools")
      };
    }

    private float score(String value, String token) {
      return value.contains(token) ? 1.0f : 0.0f;
    }
  }
}
