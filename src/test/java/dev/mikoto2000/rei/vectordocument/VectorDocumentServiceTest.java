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
import java.util.Map;

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
  void searchReturnsDistinctDocumentsEvenWhenTopChunksAreFromSameDocument() {
    TestFixture fixture = newFixture();
    fixture.vectorStore().add(List.of(
        new Document("doc-1#0", "Spring AI guide", Map.of("docId", "doc-1", "source", "/tmp/doc-1.md", "chunkIndex", 0, "ingestedAt", "2026-03-28T00:00:00Z")),
        new Document("doc-1#1", "Spring AI appendix", Map.of("docId", "doc-1", "source", "/tmp/doc-1.md", "chunkIndex", 1, "ingestedAt", "2026-03-28T00:00:00Z")),
        new Document("doc-2#0", "Spring tools memo", Map.of("docId", "doc-2", "source", "/tmp/doc-2.md", "chunkIndex", 0, "ingestedAt", "2026-03-28T00:00:00Z"))));

    List<VectorDocumentSearchResult> results = fixture.service().search("spring", 2, null, null);

    assertEquals(2, results.size());
    assertEquals("doc-1", results.get(0).docId());
    assertEquals("doc-2", results.get(1).docId());
  }

  @Test
  void searchBuildsSnippetAroundQueryInsteadOfDocumentPrefix() {
    TestFixture fixture = newFixture();
    fixture.vectorStore().add(List.of(
        new Document(
            "doc-1#0",
            "Preface text that is unrelated and keeps going before the important section begins. "
                + "Spring AI tools help build assistants with retrieval and search.",
            Map.of("docId", "doc-1", "source", "/tmp/doc-1.md", "chunkIndex", 0, "ingestedAt", "2026-03-28T00:00:00Z"))));

    List<VectorDocumentSearchResult> results = fixture.service().search("spring ai", 1, null, null);

    assertEquals(1, results.size());
    assertTrue(results.getFirst().snippet().contains("Spring AI tools"));
    assertTrue(results.getFirst().snippet().startsWith("..."));
    assertFalse(results.getFirst().snippet().startsWith("Preface text"));
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

  @Test
  void addUsesConfiguredChunkSize() throws IOException {
    Path document = tempDir.resolve("long.txt");
    Files.writeString(document, "spring ".repeat(80));

    VectorDocumentService service = newServiceWithChunkSize(20);

    VectorDocumentEntry added = service.add(List.of(document.toString())).getFirst();

    assertTrue(added.chunkCount() > 1);
  }

  @Test
  void addUsesConfiguredChunkOverlap() throws IOException {
    Path document = tempDir.resolve("long.txt");
    Files.writeString(document, "spring tools ai weather ".repeat(80));

    VectorDocumentEntry withoutOverlap = newServiceWithChunkSettings(20, 0)
        .add(List.of(document.toString()))
        .getFirst();
    VectorDocumentEntry withOverlap = newServiceWithChunkSettings(20, 10)
        .add(List.of(document.toString()))
        .getFirst();

    assertTrue(withOverlap.chunkCount() > withoutOverlap.chunkCount());
  }

  private VectorDocumentService newService() {
    return newFixture().service();
  }

  private VectorDocumentService newServiceWithChunkSize(int chunkSize) {
    return newFixture(chunkSize, 0).service();
  }

  private VectorDocumentService newServiceWithChunkSettings(int chunkSize, int chunkOverlap) {
    return newFixture(chunkSize, chunkOverlap).service();
  }

  private TestFixture newFixture() {
    return newFixture(512, 0);
  }

  private TestFixture newFixture(int chunkSize) {
    return newFixture(chunkSize, 0);
  }

  private TestFixture newFixture(int chunkSize, int chunkOverlap) {
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("memory.db"));
    SqliteVectorStore vectorStore = new SqliteVectorStore(dataSource, new FakeEmbeddingModel(), new tools.jackson.databind.json.JsonMapper());
    VectorDocumentService service = new VectorDocumentService(
        vectorStore,
        vectorStore,
        Clock.fixed(Instant.parse("2026-03-28T00:00:00Z"), ZoneOffset.UTC),
        new VectorDocumentProperties(chunkSize, chunkOverlap));
    return new TestFixture(service, vectorStore);
  }

  private record TestFixture(VectorDocumentService service, SqliteVectorStore vectorStore) {
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
