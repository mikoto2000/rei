package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@org.junit.jupiter.api.Tag("integration")
class PaperWorkflowTest {
  @Test
  void glossaryIsReusedAcrossTranslationRequests() {
    var first =
        new PaperTranslationService(
            repo,
            store,
            content,
            model("{\"content\":\"原文\",\"glossary\":{\"novel term\":\"専門用語\"}}"),
            config);
    first.translate(paper, PaperTranslation.Mode.TECHNICAL, null, false, op);
    var secondModel =
        new PaperLanguageModel() {
          public String model() {
            return "other-model";
          }

          public String generate(String system, String input, PaperOperation operation) {
            assertTrue(system.contains("novel term"));
            assertTrue(system.contains("専門用語"));
            return "{\"content\":\"原文\",\"glossary\":{}}";
          }
        };
    var result =
        new PaperTranslationService(repo, store, content, secondModel, config)
            .translate(paper, PaperTranslation.Mode.NATURAL, null, false, op);
    assertEquals("専門用語", result.glossary().get("novel term"));
  }

  @Test
  void sourceRevisionChangesCacheIdentityWithoutDeletingOldVersion() {
    summaries().summarize(paper, PaperSummary.Mode.STANDARD, false, op);
    repo.saveArtifact(paper.id(), "source", "revision", "new-import", op);
    summaries().summarize(paper, PaperSummary.Mode.STANDARD, false, op);
    assertEquals(2, calls.get());
    assertEquals(
        2, repo.versions(paper.id()).stream().filter(v -> v.kind().equals("summary")).count());
  }

  @TempDir Path dir;
  PaperProperties config;
  PaperRepository repo;
  PaperArtifactStore store;
  PaperContentService content;
  Paper paper;
  PaperOperation op = PaperOperation.local("session");
  AtomicInteger calls;

  @BeforeEach
  void setup() {
    config = new PaperProperties();
    repo =
        new SqlitePaperRepository(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("db")));
    store = new PaperArtifactStore(dir.resolve("papers"), config);
    content =
        new PaperContentService(
            store,
            (u, t, m, o) -> {
              throw new PaperException(PaperException.Code.PROVIDER_TIMEOUT, "test");
            },
            new PaperExtractionService(config),
            config);
    paper =
        repo.save(
            new Paper(
                null,
                "GUI",
                List.of("Author"),
                "source",
                2025,
                "Venue",
                "10.1/example",
                null,
                null,
                null,
                true,
                null,
                "https://example.org/file.pdf",
                List.of()),
            op);
    calls = new AtomicInteger();
  }

  PaperLanguageModel model(String result) {
    return new PaperLanguageModel() {
      public String model() {
        return "test-model";
      }

      public String generate(String system, String input, PaperOperation operation) {
        calls.incrementAndGet();
        operation.check();
        return result;
      }
    };
  }

  String summary() {
    return """
    {"content":{"problem":"課題","background":"背景","contributions":["貢献"],"method":"手法","datasets":[],"evaluation":"不明","results":["結果"],"limitations":["不明"],"futureWork":[]},"evidence":[{"claim":"結果","section":"Abstract","page":0,"text":"source"}]}
    """;
  }

  PaperSummaryService summaries() {
    return new PaperSummaryService(
        repo, content, model(summary()), new PaperSummaryValidator(), config);
  }

  PaperTranslationService translations() {
    return new PaperTranslationService(
        repo, store, content, model("{\"content\":\"原文\",\"glossary\":{}}"), config);
  }

  @ParameterizedTest
  @EnumSource(PaperSummary.Mode.class)
  void summaryModesPersistAndReuse(PaperSummary.Mode mode) {
    var service = summaries();
    var result = service.summarize(paper, mode, false, op);
    assertEquals(StructuredPaper.Availability.ABSTRACT_ONLY, result.availability());
    assertFalse(result.warnings().isEmpty());
    assertEquals("source", result.evidence().getFirst().text());
    assertEquals(result, summaries().summarize(paper, mode, false, op));
    assertEquals(1, calls.get());
  }

  @Test
  void summaryRefreshAndPromptVersion() {
    summaries().summarize(paper, PaperSummary.Mode.STANDARD, false, op);
    summaries().summarize(paper, PaperSummary.Mode.STANDARD, true, op);
    config.setPromptVersion("2");
    summaries().summarize(paper, PaperSummary.Mode.STANDARD, false, op);
    assertEquals(3, calls.get());
  }

  @Test
  void modelVersionDoesNotReuseCache() {
    summaries().summarize(paper, PaperSummary.Mode.STANDARD, false, op);
    var other =
        new PaperLanguageModel() {
          public String model() {
            return "other";
          }

          public String generate(String s, String i, PaperOperation o) {
            calls.incrementAndGet();
            return summary();
          }
        };
    new PaperSummaryService(repo, content, other, new PaperSummaryValidator(), config)
        .summarize(paper, PaperSummary.Mode.STANDARD, false, op);
    assertEquals(2, calls.get());
  }

  @Test
  void rejectsFabricatedEvidence() {
    var invalid = model(summary().replace("\"text\":\"source\"", "\"text\":\"fiction\""));
    assertThrows(
        PaperException.class,
        () ->
            new PaperSummaryService(repo, content, invalid, new PaperSummaryValidator(), config)
                .summarize(paper, PaperSummary.Mode.STANDARD, false, op));
    assertTrue(repo.versions(paper.id()).stream().noneMatch(v -> v.kind().equals("summary")));
  }

  @ParameterizedTest
  @EnumSource(PaperTranslation.Mode.class)
  void translationsPersistAndReuse(PaperTranslation.Mode mode) {
    var result = translations().translate(paper, mode, null, false, op);
    assertEquals(result, translations().translate(paper, mode, null, false, op));
    assertEquals(1, calls.get());
    assertEquals("グラウンディング", result.glossary().get("grounding"));
  }

  @Test
  void translationRefreshVersionAndChunks() {
    config.setTranslationChunkSize(2);
    translations().translate(paper, PaperTranslation.Mode.TECHNICAL, null, false, op);
    assertEquals(3, calls.get());
    translations().translate(paper, PaperTranslation.Mode.TECHNICAL, null, true, op);
    assertEquals(6, calls.get());
    config.setGlossaryVersion("2");
    translations().translate(paper, PaperTranslation.Mode.TECHNICAL, null, false, op);
    assertEquals(9, calls.get());
  }

  @Test
  void glossaryCannotChangeAcrossChunks() {
    var bad = model("{\"content\":\"x\",\"glossary\":{\"grounding\":\"別訳\"}}");
    assertThrows(
        PaperException.class,
        () ->
            new PaperTranslationService(repo, store, content, bad, config)
                .translate(paper, PaperTranslation.Mode.TECHNICAL, null, false, op));
  }

  @Test
  void partialTranslationNeverBecomesCache() {
    config.setTranslationChunkSize(3);
    var bad =
        new PaperLanguageModel() {
          public String model() {
            return "m";
          }

          public String generate(String s, String i, PaperOperation o) {
            if (calls.incrementAndGet() == 2) throw new IllegalStateException();
            return "{\"content\":\"訳\",\"glossary\":{}}";
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            new PaperTranslationService(repo, store, content, bad, config)
                .translate(paper, PaperTranslation.Mode.TECHNICAL, null, false, op));
    assertTrue(repo.versions(paper.id()).stream().noneMatch(v -> v.kind().equals("translation")));
  }

  @Test
  void metadataOnlyIsNotSummarized() {
    var p =
        repo.save(
            new Paper(
                null, "Other", List.of(), null, null, null, null, null, null, null, null, null,
                null, List.of()),
            op);
    assertEquals(StructuredPaper.Availability.METADATA_ONLY, content.content(p, op).availability());
    assertThrows(
        PaperException.class, () -> summaries().summarize(p, PaperSummary.Mode.QUICK, false, op));
    assertEquals(0, calls.get());
  }

  @Test
  void cancelledWriteAndTranslationDoNotPersist() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          CancellationException.class,
          () -> translations().translate(paper, PaperTranslation.Mode.TECHNICAL, null, false, op));
      assertThrows(CancellationException.class, () -> repo.remove(paper.id(), op));
    } finally {
      Thread.interrupted();
    }
    assertTrue(repo.find(paper.id()).isPresent());
    assertEquals(0, calls.get());
  }

  @Test
  void waitingCancellationCancelsUnderlyingFuture() {
    var future = new CompletableFuture<String>();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          CancellationException.class, () -> op.await(future, java.time.Duration.ofSeconds(1)));
      assertTrue(future.isCancelled());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void purgeAfterRemoveDeletesArtifacts() {
    store.write(paper.id(), "originals", "pdf", new byte[] {1}, op);
    summaries().summarize(paper, PaperSummary.Mode.QUICK, false, op);
    var library = new PaperLibraryService(repo, store, new PaperSessionReferences(), config);
    library.remove(paper.id(), false, op);
    assertTrue(store.read(paper.id(), "originals", "pdf").isPresent());
    library.remove(paper.id(), true, op);
    assertTrue(store.read(paper.id(), "originals", "pdf").isEmpty());
    assertThrows(PaperException.class, () -> library.remove(paper.id(), true, op));
  }

  @Test
  void artifactDeleteFailureHidesPendingPurgeAndCanRetry() {
    var failing =
        new PaperArtifactStore(dir, config) {
          @Override
          public void purge(String id, PaperOperation op) {
            throw new PaperException(PaperException.Code.LIBRARY_DELETE_FAILED, "test");
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            new PaperLibraryService(repo, failing, new PaperSessionReferences(), config)
                .remove(paper.id(), true, op));
    assertTrue(repo.find(paper.id()).isEmpty());
    assertTrue(repo.search("", 50).isEmpty());
    new PaperLibraryService(repo, store, new PaperSessionReferences(), config)
        .remove(paper.id(), true, op);
  }

  @Test
  void corruptExtractedArtifactFallsBack() {
    store.write(paper.id(), "extracted", "json", "not json".getBytes(), op);
    var result = content.content(paper, op);
    assertEquals(StructuredPaper.Availability.ABSTRACT_ONLY, result.availability());
    assertEquals(2, result.warnings().size());
  }

  @Test
  void searchFallsBackAndSavesMetadata() {
    var primary =
        new OpenAlexSearchProvider(
            (u, t, m, o) -> {
              throw new PaperException(PaperException.Code.PROVIDER_RATE_LIMITED, "rate");
            },
            config);
    var fallback =
        new CrossrefSearchProvider(
            (u, t, m, o) ->
                "{\"message\":{\"items\":[{\"title\":[\"Fallback\"],\"DOI\":\"10.2/fallback\",\"published\":{\"date-parts\":[[2025]]}}]}}"
                    .getBytes(),
            config);
    var refs = new PaperSessionReferences();
    var result =
        new PaperSearchService(primary, fallback, repo, refs, config)
            .search(PaperProviderTest.query(), op);
    assertEquals(1, result.warnings().size());
    assertEquals(result.papers().getFirst().id(), refs.resolve(op.sessionId(), "1"));
    assertTrue(repo.find(result.papers().getFirst().id()).isPresent());
  }

  @Test
  void bridgingIdentifiersMergesTwoRecordsWithoutBreakingOldIds() {
    var first =
        repo.save(
            new Paper(
                null,
                "Preprint",
                List.of(),
                null,
                null,
                null,
                null,
                "2501.12345",
                null,
                null,
                null,
                null,
                null,
                List.of()),
            op);
    var joined =
        repo.save(
            new Paper(
                null,
                "Published",
                List.of(),
                null,
                null,
                null,
                paper.doi(),
                "2501.12345",
                null,
                null,
                null,
                null,
                null,
                List.of()),
            op);
    assertEquals(paper.id(), joined.id());
    assertEquals(joined.id(), repo.find(first.id()).orElseThrow().id());
    assertEquals(1, repo.search("", 50).size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "search",
        "show",
        "translate 1 --natural --literal",
        "summarize 1 --quick --detailed",
        "search GUI --limit 0",
        "search GUI --sort invalid",
        "library purge",
        "unknown"
      })
  void rejectsInvalidCommands(String input) {
    assertThrows(PaperException.class, () -> PaperCommandRequest.parse(input.split(" ")));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "search GUI --since 2025 --limit 10",
        "show 3",
        "summarize 3 --quick --refresh",
        "translate 3 --section Method --technical",
        "library",
        "library search GUI",
        "library remove 00000000-0000-0000-0000-000000000001",
        "import 1 C:\\paper.pdf"
      })
  void acceptsCommands(String input) {
    assertNotNull(PaperCommandRequest.parse(input.split(" ")));
  }
}
