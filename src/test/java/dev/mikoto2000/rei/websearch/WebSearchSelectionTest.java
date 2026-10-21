package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Web検索選択内容の結果と状態の契約を検証するテスト。 */
class WebSearchSelectionTest {
  /** 検証に使うこの機能の動作設定。 */
  WebSearchProperties properties = new WebSearchProperties();

  /** 検証で使用する結果を、指定された条件に合わせて用意する。 */
  WebSearchResult result(String title, String url, String date) {
    return new WebSearchResult(title, url, title, date);
  }

  /** Web上の情報の調査で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void enoughRelevantCandidatesStopAfterOriginalQuery() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search("Java streams", 1))
        .thenReturn(
            List.of(
                result("Java streams reference", "https://docs.oracle.com/en/java/streams", null)));
    var selected =
        WebSearchSelection.search(
            search, new WebSearchQueryPlanner(), "Java streams", 1, properties);
    assertEquals(1, selected.size());
    verify(search, times(1)).search(anyString(), anyInt());
  }

  /** Web上の情報の調査で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void officialAndVersionRequirementsCauseAdditionalSearchDespiteEnoughResults() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search("Spring 2.0.1 official", 1))
        .thenReturn(List.of(result("Spring 2.0.1 tips", "https://docs.evil.example/a", null)));
    when(search.search("Spring 2.0.1 official reference", 1))
        .thenReturn(
            List.of(
                result("Spring 2.0.1 reference", "https://docs.spring.io/2.0.1/reference", null)));
    var selected =
        WebSearchSelection.search(
            search, new WebSearchQueryPlanner(), "Spring 2.0.1 official", 1, properties);
    assertEquals(
        "docs.spring.io", java.net.URI.create(selected.getFirst().result().url()).getHost());
    verify(search).search("Spring 2.0.1 official reference", 1);
  }

  /** 最新について、日付別の根拠を必要とすることを確認する。 */
  @Test
  void latestRequiresDatedEvidenceAndMultipleSourcesRequireDifferentDomains() {
    var old = result("Java latest release", "https://docs.oracle.com/old", "2020-01-01");
    var dated =
        result(
            "Java latest release",
            "https://docs.oracle.com/new",
            LocalDate.now(ZoneOffset.UTC).toString());
    assertFalse(WebSearchSelection.sufficient("Java latest release", List.of(old), 1));
    assertTrue(WebSearchSelection.sufficient("Java latest release", List.of(dated), 1));
    assertFalse(
        WebSearchSelection.sufficient(
            "Java compare sources",
            List.of(dated, result("Java compare sources", "https://docs.oracle.com/other", null)),
            2));
  }

  /** Web上の情報の調査で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void normalizationRetainsMeaningfulQueryAndEncoding() {
    assertEquals(
        "https://example.com/a?q=a%2Fb&version=2",
        WebSearchSelection.normalizeUrl(
            "HTTPS://EXAMPLE.COM:443/x/../a?utm_source=x&q=a%2Fb&version=2#section"));
    assertNotEquals(
        WebSearchSelection.normalizeUrl("https://example.com/?q=a"),
        WebSearchSelection.normalizeUrl("https://example.com/?q=b"));
    assertNotEquals(
        WebSearchSelection.normalizeUrl("https://example.com/?q=a%2Fb"),
        WebSearchSelection.normalizeUrl("https://example.com/?q=a/b"));
    assertEquals(
        "https://example.com/?ref=main&source=code",
        WebSearchSelection.normalizeUrl("https://example.com?ref=main&source=code"));
  }

  /** 重複一覧について、別名一覧を維持することを確認する。 */
  @Test
  void duplicatesKeepAliasesAndCandidatesAreCappedBeforeFetch() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search(anyString(), anyInt()))
        .thenReturn(
            List.of(
                result("Java streams", "https://docs.oracle.com/a?utm_source=one", null),
                result("Java streams", "https://docs.oracle.com/a?utm_source=two", null),
                result("Java streams", "https://example.org/b", null),
                result("Java streams", "https://example.net/c", null)));
    var selected =
        WebSearchSelection.search(
            search, new WebSearchQueryPlanner(), "Java streams", 2, properties);
    assertEquals(2, selected.size());
    assertEquals(2, selected.getFirst().aliases().size());
    assertEquals("https://example.org/b", selected.get(1).result().url());
  }

  /** Web上の情報の調査で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unrelatedResultsAndWrongVersionsAreNotSufficient() {
    assertFalse(
        WebSearchSelection.sufficient(
            "Java streams", List.of(result("weather forecast", "https://example.com", null)), 1));
    assertFalse(
        WebSearchSelection.sufficient(
            "Spring 2.0.1 API",
            List.of(result("Spring 1.0 API", "https://docs.spring.io/1.0", null)),
            1));
    assertTrue(
        WebSearchSelection.sufficient(
            "Spring 2.0.1 API",
            List.of(result("Spring 2.0.1 API", "https://docs.spring.io/2.0.1", null)),
            1));
    assertFalse(WebSearchSelection.official("JavaScript official", "https://docs.oracle.com/java"));
    assertFalse(WebSearchSelection.knownOfficial("https://docs.evil.example"));
    assertFalse(WebSearchSelection.knownOfficial("https://agency.gov.evil.example"));
  }

  /** Web上の情報の調査の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void expansionIsBoundedAndCancellationPropagates() throws Exception {
    var search = mock(WebSearchService.class);
    when(search.search(anyString(), anyInt())).thenReturn(List.of());
    assertTrue(
        WebSearchSelection.search(search, new WebSearchQueryPlanner(), "rare result", 2, properties)
            .isEmpty());
    verify(search, atMost(3)).search(anyString(), anyInt());
    when(search.search(anyString(), anyInt()))
        .thenThrow(new java.util.concurrent.CancellationException());
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () ->
            WebSearchSelection.search(
                search, new WebSearchQueryPlanner(), "rare result", 2, properties));
  }
}
