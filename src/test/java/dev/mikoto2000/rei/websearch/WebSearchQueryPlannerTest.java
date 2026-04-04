package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class WebSearchQueryPlannerTest {

  @Test
  void planReturnsExpandedQueriesWithoutDuplicates() {
    WebSearchQueryPlanner planner = new WebSearchQueryPlanner();

    List<String> queries = planner.plan("Spring AI 使い方");

    assertEquals("Spring AI 使い方", queries.getFirst());
    assertTrue(queries.contains("Spring AI 使い方 official"));
    assertTrue(queries.contains("Spring AI 使い方 latest"));
    assertEquals(queries.size(), queries.stream().distinct().count());
  }
}
