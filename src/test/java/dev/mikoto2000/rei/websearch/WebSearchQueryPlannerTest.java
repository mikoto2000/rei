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
    assertTrue(queries.contains("Spring AI 使い方 reference"));
    org.junit.jupiter.api.Assertions.assertFalse(queries.contains("Spring AI 使い方 latest"));
    assertEquals(queries.size(), queries.stream().distinct().count());
  }
  @Test void latestIntentUsesDateQueryAndOfficialIntentDoesNotRepeatTheWord() {
    assertTrue(new WebSearchQueryPlanner().plan("Java latest release").contains("Java latest release release date"));
    org.junit.jupiter.api.Assertions.assertFalse(new WebSearchQueryPlanner().plan("Spring official").contains("Spring official official"));
  }
}
