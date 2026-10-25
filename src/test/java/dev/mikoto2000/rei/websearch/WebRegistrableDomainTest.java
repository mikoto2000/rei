package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** PSL の境界条件と検索候補・本文の複数出典判定を検証する。 */
class WebRegistrableDomainTest {
  @ParameterizedTest
  @CsvSource({
    "https://www.example.com/page, example.com",
    "https://docs.example.com/page, example.com",
    "https://a.co.jp, a.co.jp",
    "https://www.a.co.jp, a.co.jp",
    "https://b.co.jp, b.co.jp",
    "https://sub.example.co.uk, example.co.uk",
    "https://WWW.Example.COM:8443/page, example.com",
    "https://www.example.com./page, example.com",
    "https://www.例え.jp/page, xn--r8jz45g.jp",
    "https://www.xn--r8jz45g.jp/page, xn--r8jz45g.jp",
    "http://localhost:8080/page, localhost",
    "http://127.0.0.1:8080/page, 127.0.0.1",
    "http://127.0.0.2/page, 127.0.0.2",
    "https://[2001:4860:4860:0:0:0:0:8888]:443/page, 2001:4860:4860::8888",
    "https://[2001:4860:4860::8844]/page, 2001:4860:4860::8844",
    "https://co.jp/page, co.jp",
    "https://com/page, com",
    "https://one.unknownsuffix/page, one.unknownsuffix",
    "https://two.unknownsuffix/page, two.unknownsuffix",
    "https://one.github.io/page, one.github.io",
    "https://two.github.io/page, two.github.io",
    "https://a.b.ck/page, a.b.ck",
    "https://a.www.ck/page, www.ck"
  })
  void domainUsesPslOrNormalizedHost(String url, String expected) {
    assertEquals(expected, WebSearchSelection.domain(url));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "not a url",
        "https:///missing",
        "https://bad..com",
        "https://-bad.com",
        "https://bad_host.com",
        "https://example.com:99999",
        "https://[broken",
        "file:///tmp/example",
        "https://user:secret@example.com",
        "https://999.999.999.999"
      })
  void invalidUrlHasNoSourceIdentity(String url) {
    assertNull(WebSearchSelection.domain(url));
  }

  private WebSearchResult result(String host) {
    return new WebSearchResult("Java streams", "https://" + host + "/page", "Java streams", null);
  }

  private WebSearchSelection.Candidate candidate(String host, int rank) {
    var result = result(host);
    return new WebSearchSelection.Candidate(result, List.of(WebSourceAlias.from(result)), rank);
  }

  private WebSearchPage page(String host) {
    var result = result(host);
    String body = "Java streams guide " + host;
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

  @Test
  void selectionPreservesDifferentJapaneseCompanies() {
    var selected =
        WebSearchSelection.choose(
            "Java streams",
            List.of(
                candidate("www.a.co.jp", 0), candidate("docs.a.co.jp", 1), candidate("b.co.jp", 2)),
            2);
    assertEquals(
        List.of("https://www.a.co.jp/page", "https://b.co.jp/page"),
        selected.stream().map(value -> value.result().url()).toList());
  }

  @Test
  void candidateSufficiencyUsesRegistrableDomains() {
    assertTrue(
        WebSearchSelection.sufficient(
            "compare Java streams sources", List.of(result("a.co.jp"), result("b.co.jp")), 2));
    assertFalse(
        WebSearchSelection.sufficient(
            "compare Java streams sources",
            List.of(result("www.a.co.jp"), result("docs.a.co.jp")),
            2));
  }

  @Test
  void bodyAssessmentCountsDifferentCompaniesAndGroupsSubdomains() {
    var independent =
        WebRetrievalAssessment.assess(
            "compare Java streams sources", List.of(page("www.a.co.jp"), page("b.co.jp")));
    assertEquals(2, independent.independentSources());
    assertEquals("sufficient", independent.status());
    var same =
        WebRetrievalAssessment.assess(
            "compare Java streams sources", List.of(page("www.a.co.jp"), page("docs.a.co.jp")));
    assertEquals(1, same.independentSources());
    assertTrue(same.reasons().contains("independent_sources_missing"));
  }

  @Test
  void malformedSourcesDoNotCountAsIndependentEvidence() {
    var assessment =
        WebRetrievalAssessment.assess(
            "compare Java streams sources", List.of(page("bad..com"), page("-bad.com")));
    assertEquals(0, assessment.independentSources());
    assertTrue(assessment.reasons().contains("independent_sources_missing"));
  }
}
