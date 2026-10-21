package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Web検索検索条件計画の作成の結果と状態の契約を検証するテスト。 */
class WebSearchQueryPlannerTest {

  /** Web上の情報の調査で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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

  /** 最新意図について、日付検索条件を利用することを確認する。 */
  @Test
  void latestIntentUsesDateQueryAndOfficialIntentDoesNotRepeatTheWord() {
    assertTrue(
        new WebSearchQueryPlanner()
            .plan("Java latest release")
            .contains("Java latest release release date"));
    org.junit.jupiter.api.Assertions.assertFalse(
        new WebSearchQueryPlanner().plan("Spring official").contains("Spring official official"));
  }
}
