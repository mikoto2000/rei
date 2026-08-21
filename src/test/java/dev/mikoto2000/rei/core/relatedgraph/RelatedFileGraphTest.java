package dev.mikoto2000.rei.core.relatedgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class RelatedFileGraphTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  private RelatedFileGraph graph(int maxRelations, Instant start) {
    return new RelatedFileGraph(maxRelations, Clock.fixed(start, ZONE));
  }

  private OffsetDateTime at(Instant instant) {
    return instant.atZone(ZONE).toOffsetDateTime();
  }

  @Test
  void relationCanBeAdded() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    assertFalse(graph.isEmpty());
    assertEquals(1, graph.size());
  }

  @Test
  void relatedFilesCanBeRetrievedByPath() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserServiceTest.java", "src/UserService.java", "TESTS", "SEARCH");

    List<FileRelation> related = graph.getRelated("src/UserService.java");
    assertEquals(2, related.size());
    assertTrue(related.stream().anyMatch(r -> r.sourcePath().equals("src/UserController.java")));
    assertTrue(related.stream().anyMatch(r -> r.sourcePath().equals("src/UserServiceTest.java")));
  }

  @Test
  void sameRelationIsNotDuplicated() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    assertEquals(1, graph.size());
  }

  @Test
  void differentTypeIsDifferentRelation() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserController.java", "src/UserService.java", "IMPORTS", "SEARCH");
    assertEquals(2, graph.size());
  }

  @Test
  void sourceFileChangeRemovesRelations() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.removeRelationsFor("src/UserController.java");
    assertTrue(graph.isEmpty());
  }

  @Test
  void targetFileChangeRemovesRelations() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.removeRelationsFor("src/UserService.java");
    assertTrue(graph.isEmpty());
  }

  @Test
  void deleteRemovesAllRelationsForFile() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/UserServiceTest.java", "src/UserService.java", "TESTS", "SEARCH");
    graph.removeRelationsFor("src/UserService.java");
    assertTrue(graph.isEmpty());
  }

  @Test
  void maxRelationsEvictsOldest() {
    Instant start = Instant.parse("2026-08-17T00:00:00Z");
    RelatedFileGraph graph = graph(2, start);
    graph.addRelation("a", "b", "REFERENCES", "SEARCH");
    graph.addRelation("c", "d", "REFERENCES", "SEARCH");
    graph.addRelation("e", "f", "REFERENCES", "SEARCH");
    assertEquals(2, graph.size());
    assertTrue(graph.getRelated("a").isEmpty());
    assertTrue(graph.getRelated("c").size() == 1);
    assertTrue(graph.getRelated("e").size() == 1);
  }

  @Test
  void promptRendersOnlyRelationsForWorkingSetFiles() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    graph.addRelation("src/Other.java", "src/Unrelated.java", "REFERENCES", "SEARCH");

    String prompt = graph.renderForPrompt(java.util.Set.of("src/UserController.java"));
    assertTrue(prompt.contains("src/UserController.java"));
    assertTrue(prompt.contains("src/UserService.java"));
    assertFalse(prompt.contains("src/Other.java"));
  }

  @Test
  void emptyGraphRendersBlank() {
    RelatedFileGraph graph = graph(100, Instant.parse("2026-08-17T00:00:00Z"));
    assertEquals("", graph.renderForPrompt(java.util.Set.of("src/UserController.java")));
  }

  @Test
  void lastConfirmedAtIsUpdatedOnReconfirmation() {
    Instant start = Instant.parse("2026-08-17T00:00:00Z");
    RelatedFileGraph graph = graph(100, start);
    graph.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    FileRelation first = graph.relations().get(0);
    assertEquals(at(start), first.lastConfirmedAt());

    Instant later = Instant.parse("2026-08-17T01:00:00Z");
    RelatedFileGraph graph2 = graph(100, later);
    graph2.addRelation("src/UserController.java", "src/UserService.java", "REFERENCES", "SEARCH");
    FileRelation second = graph2.relations().get(0);
    assertEquals(at(later), second.lastConfirmedAt());
    assertEquals(1, graph2.size());
  }
}