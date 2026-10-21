package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Web検索Orchestrator上限の結果と状態の契約を検証するテスト。 */
class WebSearchOrchestratorLimitTest {
  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void pageBudgetIsAppliedBeforeFetching() throws Exception {
    var search = mock(WebSearchService.class);
    var fetch = mock(WebPageFetcher.class);
    var properties = new WebSearchProperties();
    properties.setMaxPageFetches(1);
    var sources =
        java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> new WebSearchResult("Java", "https://example.com/" + i, "Java", null))
            .toList();
    when(search.search("Java", 3)).thenReturn(sources);
    when(fetch.fetch(sources.getFirst()))
        .thenReturn(new WebSearchPage("Java", sources.getFirst().url(), "Java", null, "evidence"));
    var context =
        new WebSearchOrchestrator(
                search, fetch, new WebSearchQueryPlanner(), new WebSearchAggregator(), properties)
            .search("Java", 3);
    assertEquals(1, context.allResults().size());
    verify(fetch, times(1)).fetch(any());
  }

  /** Web上の情報の調査の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationAfterMetadataStartsNoPageFetch() throws Exception {
    var search = mock(WebSearchService.class);
    var fetch = mock(WebPageFetcher.class);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(search.search(anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              cancelled.set(true);
              return List.of(new WebSearchResult("Java", "https://example.com/", "Java", null));
            });
    try (var scope =
        dev.mikoto2000.rei.http.FetchScope.enter(
            new dev.mikoto2000.rei.http.FetchOperation(
                () -> {
                  if (cancelled.get()) throw new java.util.concurrent.CancellationException();
                },
                Long.MAX_VALUE))) {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () ->
              new WebSearchOrchestrator(
                      search, fetch, new WebSearchQueryPlanner(), new WebSearchAggregator())
                  .search("Java", 1));
    }
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fetch);
  }
}
