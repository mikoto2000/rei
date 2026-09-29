package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PaperLibraryTest {
  @TempDir Path dir;

  PaperRepository repository() {
    return new SqlitePaperRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db")));
  }

  Paper paper(String title, String doi) {
    return new Paper(
        null,
        title,
        List.of("Alice"),
        "GUI abstract",
        2025,
        "Venue",
        doi,
        null,
        null,
        10L,
        true,
        null,
        null,
        List.of("agent"));
  }

  @Test
  void persistsAcrossRestartAndMergesNormalizedDoi() {
    var repo = repository();
    var first = repo.save(paper("Title", "https://doi.org/10.1234/ABC"), PaperOperation.local("s"));
    var second = repository().save(paper("Updated", "10.1234/abc"), PaperOperation.local("s"));
    assertEquals(first.id(), second.id());
    assertEquals("Updated", repo.find(first.id()).orElseThrow().title());
    assertEquals(1, repo.search("", 50).size());
  }

  @Test
  void deduplicatesUnicodeTitleButNotDifferentAuthorOrYear() {
    var repo = repository();
    var op = PaperOperation.local("s");
    var a = repo.save(paper("  ＧＵＩ   Agent  ", null), op);
    assertEquals(a.id(), repo.save(paper("gui agent", null), op).id());
    assertNotEquals(
        a.id(),
        repo.save(
                new Paper(
                    null,
                    "gui agent",
                    List.of("Bob"),
                    null,
                    2025,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of()),
                op)
            .id());
  }

  @Test
  void searchAllFieldsAndLimitAndRemove() {
    var repo = repository();
    var p = repo.save(paper("ShowUI", "10.1234/test"), PaperOperation.local("s"));
    for (String q : List.of("ShowUI", "Alice", "GUI", "2025", "10.1234/test", "Venue", "agent"))
      assertEquals(1, repo.search(q, 10).size(), q);
    assertTrue(repo.search("absent", 10).isEmpty());
    repo.remove(p.id(), PaperOperation.local("s"));
    assertTrue(repo.search("", 10).isEmpty());
    assertTrue(repo.find(p.id()).isEmpty());
  }

  @Test
  void artifactsAreAtomicValidatedAndSurviveRestart() {
    var store = new PaperArtifactStore(dir, new PaperProperties());
    var id = UUID.randomUUID().toString();
    var op = PaperOperation.local("s");
    store.write(id, "extracted", "json", "hello".getBytes(), op);
    assertEquals(
        "hello",
        new String(
            new PaperArtifactStore(dir, new PaperProperties())
                .read(id, "extracted", "json")
                .orElseThrow()));
    assertThrows(PaperException.class, () -> store.read("../../outside", "extracted", "json"));
    assertThrows(PaperException.class, () -> store.read(id, "../outside", "json"));
    assertTrue(store.read(UUID.randomUUID().toString(), "extracted", "json").isEmpty());
  }

  @Test
  void validatesQueryAndSessionReferences() {
    assertThrows(
        PaperException.class,
        () ->
            new PaperSearchQuery(
                "gui",
                null,
                null,
                List.of(),
                List.of(),
                false,
                PaperSearchQuery.Sort.RELEVANCE,
                101));
    assertThrows(
        PaperException.class,
        () ->
            new PaperSearchQuery(
                "gui", 2026, 2025, List.of(), List.of(), false, PaperSearchQuery.Sort.NEWEST, 10));
    var refs = new PaperSessionReferences();
    var id = UUID.randomUUID().toString();
    refs.replace("a", List.of(id));
    assertEquals(id, refs.resolve("a", "1"));
    assertThrows(PaperException.class, () -> refs.resolve("b", "1"));
  }
}
