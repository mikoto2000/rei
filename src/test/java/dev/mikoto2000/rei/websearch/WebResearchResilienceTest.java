package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.http.HttpFetchException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 追加検索の障害後も、制限と診断情報を維持して既存候補を取得する契約。 */
class WebResearchResilienceTest {
  private final WebSearchService search = mock(WebSearchService.class);
  private final WebSearchProperties properties = new WebSearchProperties();
  private final List<String> fetched = new CopyOnWriteArrayList<>();
  private static final String QUERY = "Java streams";

  WebResearchResilienceTest() {
    properties.setInitialPageFetches(1);
  }

  private WebSearchResult result(int index) {
    return new WebSearchResult(
        "Java streams", "https://site" + index + ".com/page", "Java streams", null);
  }

  private List<WebSearchResult> candidates() {
    return java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(this::result).toList();
  }

  private WebSearchPage page(WebSearchResult result, String body) {
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

  private WebResearchPipeline.Research<WebSearchPage> run(
      int readTop, boolean complete, boolean failSecond) throws Exception {
    try (var batch = new WebFetchBatch(1, 1, 8)) {
      return WebResearchPipeline.run(
          search,
          new WebSearchQueryPlanner(),
          properties,
          batch,
          QUERY,
          5,
          readTop,
          candidate -> {
            String url = candidate.result().url();
            fetched.add(url);
            if (failSecond && url.equals(result(2).url()))
              throw new IOException("page unavailable");
            return page(
                candidate.result(),
                (complete ? "Java streams guide " : "Java partial guide ") + url);
          },
          WebResearchPipeline.PAGES);
    }
  }

  private void outage(Exception failure) throws Exception {
    when(search.search(QUERY, 5)).thenReturn(candidates());
    when(search.search(QUERY + " official", 5)).thenThrow(failure);
    when(search.search(QUERY + " reference", 5)).thenThrow(failure);
  }

  /** 正常時は充足した時点で止まり、追加検索をしない。 */
  @Test
  void normalSuccessStopsWithoutSupplement() throws Exception {
    when(search.search(QUERY, 5)).thenReturn(candidates());
    var research = run(5, true, false);
    assertEquals("sufficient", research.assessment().status());
    assertEquals(1, research.pageAttempts());
    assertEquals(List.of(), research.omissions());
    verify(search, times(1)).search(anyString(), eq(5));
  }

  /** 追加検索に成功した場合は新しい候補を優先する。 */
  @Test
  void successfulSupplementFetchesNewCandidateFirst() throws Exception {
    when(search.search(QUERY, 5)).thenReturn(candidates());
    when(search.search(QUERY + " official", 5)).thenReturn(List.of(result(6)));
    run(2, false, false);
    assertEquals(List.of(result(1).url(), result(6).url()), fetched);
  }

  static List<Exception> providerFailures() {
    return List.of(
        new IOException("secret must not be logged"),
        new IllegalStateException("provider unavailable"));
  }

  /** 連続する検索障害でも候補を一度ずつ取得し、取得済み本文と障害診断を保持する。 */
  @ParameterizedTest
  @MethodSource("providerFailures")
  void repeatedSupplementFailuresDrainExistingCandidates(Exception failure) throws Exception {
    outage(failure);
    var research = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> run(5, false, false));
    assertEquals(5, fetched.size());
    assertEquals(5, fetched.stream().distinct().count());
    assertEquals(5, research.pageAttempts());
    assertEquals(3, research.queries());
    assertTrue(research.results().stream().allMatch(page -> "success".equals(page.fetchStatus())));
    assertTrue(
        research.results().stream().anyMatch(page -> page.content().contains(result(1).url())));
    assertTrue(research.omissions().contains("additional_search_failed"));
    assertEquals("unknown", research.assessment().status());
    verify(search, times(3)).search(anyString(), eq(5));
  }

  /** 本文取得の一部失敗はページ単位で残し、後続候補の取得を妨げない。 */
  @Test
  void failedPageDoesNotBlockRemainingCandidates() throws Exception {
    outage(new IOException("provider unavailable"));
    var research = run(5, false, true);
    assertEquals(5, fetched.size());
    assertEquals(
        4,
        research.results().stream().filter(page -> "success".equals(page.fetchStatus())).count());
    var failed =
        research.results().stream()
            .filter(page -> page.url().equals(result(2).url()))
            .findFirst()
            .orElseThrow();
    assertEquals("failed", failed.fetchStatus());
    assertEquals("FETCH_ERROR", failed.errorType());
    assertTrue(research.omissions().contains("additional_search_failed"));
  }

  /** ページ予算と検索条件の上限は障害時にも維持する。 */
  @Test
  void outageRespectsReadAndQueryLimits() throws Exception {
    outage(new IOException("provider unavailable"));
    properties.setMaxPageFetches(2);
    properties.setMaxSearchQueries(2);
    var research = run(5, false, false);
    assertEquals(2, fetched.size());
    assertEquals(2, research.pageAttempts());
    assertEquals(2, research.queries());
    assertEquals(
        3,
        research.results().stream()
            .filter(page -> "not_requested".equals(page.fetchStatus()))
            .count());
  }

  /** 空の検索結果は候補取得をせず有限回で終了する。 */
  @Test
  void noCandidatesTerminates() throws Exception {
    when(search.search(anyString(), eq(5))).thenReturn(List.of());
    var research = run(5, false, false);
    assertEquals(List.of(), research.results());
    assertEquals(0, research.pageAttempts());
    assertEquals(3, research.queries());
  }

  static List<Exception> cancellations() {
    return List.of(
        new CancellationException(),
        new InterruptedException(),
        new IOException(new InterruptedException()),
        new IllegalStateException(new CancellationException()));
  }

  /** 直接・ラップされたキャンセルと中断は通常の障害として扱わない。 */
  @ParameterizedTest
  @MethodSource("cancellations")
  void cancellationPropagates(Exception failure) throws Exception {
    outage(failure);
    try {
      Class<? extends Throwable> expected =
          failure instanceof InterruptedException
              ? InterruptedException.class
              : CancellationException.class;
      assertThrows(expected, () -> run(5, false, false));
      assertEquals(1, fetched.size());
    } finally {
      Thread.interrupted();
    }
  }

  static List<HttpFetchException.Code> limits() {
    return List.of(HttpFetchException.Code.TOTAL_TIMEOUT, HttpFetchException.Code.TRANSFER_BUDGET);
  }

  /** 時間・転送・要求予算の終了は通常の障害と区別し、既存本文を保持する。 */
  @ParameterizedTest
  @MethodSource("limits")
  void controlLimitsStopRetrieval(HttpFetchException.Code code) throws Exception {
    outage(new HttpFetchException(code));
    var research = run(5, false, false);
    assertEquals(1, fetched.size());
    assertEquals(1, research.results().size());
    assertEquals("success", research.results().getFirst().fetchStatus());
    assertTrue(research.omissions().contains(code.name()));
    assertFalse(research.omissions().contains("additional_search_failed"));
  }

  @Test
  void searchRequestLimitStillAllowsPendingPages() throws Exception {
    outage(new HttpFetchException(HttpFetchException.Code.REQUEST_BUDGET));
    var research = run(5, false, false);
    assertEquals(5, fetched.size());
    assertEquals(3, research.queries());
    assertFalse(research.omissions().contains("additional_search_failed"));
  }

  @Test
  void initialFailurePropagates() throws Exception {
    when(search.search(QUERY, 5)).thenThrow(new IOException("initial search failed"));
    assertThrows(IOException.class, () -> run(5, false, false));
    assertEquals(List.of(), fetched);
  }

  @Test
  void failureLogContainsOnlySafeCauseType() throws Exception {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("dev.mikoto2000.rei.websearch");
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      outage(new IOException("secret must not be logged"));
      run(5, false, false);
      var messages =
          appender.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .toList();
      assertTrue(
          messages.stream()
              .anyMatch(
                  message ->
                      message.contains("additional_search") && message.contains("IOException")));
      assertTrue(
          messages.stream().noneMatch(message -> message.contains("secret must not be logged")));
      assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void partialSearchResultsDoNotSwallowWrappedInterruption() throws Exception {
    when(search.search(QUERY, 5)).thenReturn(List.of(result(1)));
    when(search.search(QUERY + " official", 5))
        .thenThrow(new IOException(new InterruptedException()));
    try {
      assertThrows(CancellationException.class, () -> run(5, false, false));
      assertEquals(List.of(), fetched);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @ParameterizedTest
  @MethodSource("providerFailures")
  void partialSupplementRetainsCandidatesAndReportsLaterFailure(Exception failure)
      throws Exception {
    when(search.search(QUERY, 5)).thenReturn(candidates());
    when(search.search(QUERY + " official", 5)).thenReturn(List.of(result(6)));
    when(search.search(QUERY + " reference", 5)).thenThrow(failure);
    var research = run(2, false, false);
    assertEquals(List.of(result(1).url(), result(6).url()), fetched);
    assertTrue(research.omissions().contains("additional_search_failed"));
    assertEquals("unknown", research.assessment().status());
    assertEquals(3, research.queries());
  }

  @ParameterizedTest
  @MethodSource("limits")
  void partialSupplementDoesNotSwallowGlobalLimits(HttpFetchException.Code code) throws Exception {
    when(search.search(QUERY, 5)).thenReturn(candidates());
    when(search.search(QUERY + " official", 5)).thenReturn(List.of(result(6)));
    when(search.search(QUERY + " reference", 5)).thenThrow(new HttpFetchException(code));
    var research = run(5, false, false);
    assertEquals(List.of(result(1).url()), fetched);
    assertTrue(research.omissions().contains(code.name()));
    assertFalse(research.omissions().contains("additional_search_failed"));
  }
}
