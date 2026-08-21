package dev.mikoto2000.rei.core.relatedgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.OffsetDateTime;

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
}