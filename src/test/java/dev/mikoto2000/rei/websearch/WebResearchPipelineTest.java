package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Web調査処理の連携の結果と状態の契約を検証するテスト。 */
class WebResearchPipelineTest {
  @Test
  void readsIndependentUnreadSourcesConcurrentlyWithinRemainingBudget() throws Exception {
    var search = mock(WebSearchService.class);
    var properties = new WebSearchProperties();
    properties.setInitialPageFetches(1);
    String query = "compare Java streams sources";
    var first = result("one.com");
    var second = result("two.com");
    var third = result("three.com");
    when(search.search(query, 5))
        .thenReturn(
            List.of(first, result("other.one.com"), second, third, result("four.com")));
    var started = new CountDownLatch(2);
    Set<String> fetched = ConcurrentHashMap.newKeySet();

    try (var batch = new WebFetchBatch(3, 2, 4)) {
      var research =
          WebResearchPipeline.run(
              search,
              new WebSearchQueryPlanner(),
              properties,
              batch,
              query,
              5,
              3,
              candidate -> {
                var result = candidate.result();
                fetched.add(result.url());
                if (result.equals(first)) return page(result, "Java streams support map.");
                started.countDown();
                assertTrue(started.await(3, TimeUnit.SECONDS), "Independent reads must overlap");
                return page(result, "Java streams filtering on " + result.url());
              },
              WebResearchPipeline.PAGES);
      assertEquals(Set.of(first.url(), second.url(), third.url()), fetched);
      assertEquals(3, research.pageAttempts());
      assertEquals("sufficient", research.assessment().status());
      assertEquals(1, research.queries());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void readsPromisingCandidateBeforeSearchingAgain(boolean fillsGap) throws Exception {
    var search = mock(WebSearchService.class);
    var planner = mock(WebSearchQueryPlanner.class);
    var properties = new WebSearchProperties();
    String query = "Java streams";
    String supplemental = query + " reference";
    var unread = result("unread.example");
    var replacement = result("replacement.example");
    var unreadReads = new AtomicInteger();
    when(planner.plan(query)).thenReturn(List.of(query, supplemental));
    when(search.search(query, 3))
        .thenReturn(List.of(result("one.example"), result("two.example"), unread));
    when(search.search(supplemental, 3))
        .thenAnswer(
            invocation -> {
              assertEquals(1, unreadReads.get());
              return List.of(replacement);
            });

    try (var batch = new WebFetchBatch(2, 2, 4)) {
      var research =
          WebResearchPipeline.run(
              search,
              planner,
              properties,
              batch,
              query,
              3,
              4,
              candidate -> {
                var result = candidate.result();
                if (result.equals(unread)) unreadReads.incrementAndGet();
                if (result.equals(replacement) || (result.equals(unread) && fillsGap))
                  return page(result, "Java streams support map and filter.");
                return page(result, "Java overview without details.");
              },
              WebResearchPipeline.PAGES);
      assertEquals(1, unreadReads.get());
      assertEquals("sufficient", research.assessment().status());
      assertEquals(fillsGap ? 1 : 2, research.queries());
      verify(search, times(fillsGap ? 0 : 1)).search(supplemental, 3);
    }
  }

  /** 既に取得根拠を保持することを確認する。 */
  @Test
  void supplementalProviderOutageRetainsAlreadyFetchedEvidence() throws Exception {
    var search = mock(WebSearchService.class);
    var properties = new WebSearchProperties();
    String query = "Java streams";
    when(search.search(query, 2)).thenReturn(List.of(result("one.example"), result("two.example")));
    when(search.search(query + " official", 2))
        .thenThrow(new java.io.IOException("provider failed"));
    try (var batch = new WebFetchBatch(2, 2, 4)) {
      var research =
          WebResearchPipeline.run(
              search,
              new WebSearchQueryPlanner(),
              properties,
              batch,
              query,
              2,
              4,
              candidate ->
                  page(candidate.result(), "Java partial guide " + candidate.result().url()),
              WebResearchPipeline.PAGES);
      assertEquals(2, research.results().size());
      assertEquals("unknown", research.assessment().status());
      assertTrue(research.omissions().contains("additional_search_failed"));
      assertEquals(2, research.queries());
    }
  }

  /** 検証で使用する結果を、指定された条件に合わせて用意する。 */
  WebSearchResult result(String host) {
    return new WebSearchResult(
        "Java streams", "https://" + host + "/page", "Java streams reference", null);
  }

  /** {@code page} の条件を再現するための補助情報を返す。 */
  WebSearchPage page(WebSearchResult result, String body) {
    return new WebSearchPage(
        result.title(),
        result.url(),
        result.snippet(),
        null,
        body,
        false,
        WebContentDeduplication.fingerprint(body),
        List.of(WebSourceAlias.from(result)));
  }

  /** Web上の情報の調査で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void bodyDuplicatesTriggerOneBoundedSupplementAndRetainIndependentEvidence() throws Exception {
    var search = mock(WebSearchService.class);
    var planner = mock(WebSearchQueryPlanner.class);
    var properties = new WebSearchProperties();
    String query = "compare Java streams sources";
    when(planner.plan(query)).thenReturn(List.of(query, query + " official", query + " reference"));
    var initial =
        List.of(
            result("one.example"),
            result("two.example"),
            result("three.example"),
            result("four.example"),
            result("five.example"));
    var extra = List.of(result("six.example"), result("seven.example"));
    when(search.search(query, 5)).thenReturn(initial);
    when(search.search(query + " official", 5)).thenReturn(extra);
    var reads = new AtomicInteger();
    try (var batch = new WebFetchBatch(3, 2, 10)) {
      var research =
          WebResearchPipeline.run(
              search,
              planner,
              properties,
              batch,
              query,
              5,
              5,
              candidate -> {
                reads.incrementAndGet();
                return page(
                    candidate.result(),
                    candidate.result().url().contains("six")
                        ? "Java streams filtering independent guide."
                        : "Java streams map copied explanation.");
              },
              WebResearchPipeline.PAGES);
      assertEquals("sufficient", research.assessment().status());
      assertTrue(reads.get() <= 5);
      assertTrue(research.results().stream().anyMatch(value -> value.url().contains("six")));
      verify(search).search(query + " official", 5);
      verify(search, atMost(3)).search(anyString(), eq(5));
    }
  }

  /** 上限一覧について、繰り返された失敗情報を停止することを確認する。 */
  @Test
  void limitsStopRepeatedFailureWithoutRefetchingTheSameNormalizedUrl() throws Exception {
    var search = mock(WebSearchService.class);
    var planner = mock(WebSearchQueryPlanner.class);
    var properties = new WebSearchProperties();
    properties.setMaxSearchQueries(2);
    properties.setMaxPageFetches(2);
    when(planner.plan("topic")).thenReturn(List.of("topic", "topic official", "topic reference"));
    when(search.search(anyString(), eq(3)))
        .thenReturn(
            List.of(
                new WebSearchResult(
                    "topic", "https://one.example/page?utm_source=x", "topic", null)));
    var reads = new AtomicInteger();
    try (var batch = new WebFetchBatch(1, 1, 4)) {
      var research =
          WebResearchPipeline.run(
              search,
              planner,
              properties,
              batch,
              "topic",
              3,
              2,
              candidate -> {
                reads.incrementAndGet();
                throw new IllegalStateException("unavailable");
              },
              WebResearchPipeline.PAGES);
      assertEquals("unknown", research.assessment().status());
      assertEquals(1, reads.get());
      verify(search, times(2)).search(anyString(), eq(3));
    }
  }
}
