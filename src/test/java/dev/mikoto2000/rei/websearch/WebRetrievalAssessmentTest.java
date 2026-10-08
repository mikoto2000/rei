package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebRetrievalAssessmentTest {
  @Test void redirectedDuplicateCannotChangeTheRepresentativesAuthority() {
    var representative=page("https://blog.example/page","Java official explanation");
    var copy=page("https://copy.example/page","Java official explanation")
        .withAliases(List.of(new WebSourceAlias("https://oracle.com/page","copy",null,"http_redirect")));
    var assessment=WebRetrievalAssessment.assess("Java official",WebContentDeduplication.pages(List.of(representative,copy)));
    assertTrue(assessment.reasons().contains("official_source_missing"));
  }
  @Test void oldOrMissingPublicationDatesDoNotMeetFreshnessRequirements() {
    var old=new WebSearchPage("Java release","https://oracle.com/page","","2000-01-01","Java release information",false,
        "hash",List.of());
    assertTrue(WebRetrievalAssessment.assess("latest Java release",List.of(old)).reasons().contains("publication_date_stale_or_invalid"));
    assertTrue(WebRetrievalAssessment.assess("latest Java release",List.of(page("https://oracle.com/page","Java release information")))
        .reasons().contains("publication_date_unknown"));
  }
  WebSearchPage page(String url, String text) {
    return new WebSearchPage("source", url, "", null, text, false, WebContentDeduplication.fingerprint(text),
        List.of(new WebSourceAlias(url, "source", null)));
  }
  @Test void matchingReadableEvidenceCanSatisfyRetrievalWithoutClaimingVerifiedTruth() {
    var assessment = WebRetrievalAssessment.assess("Java streams", List.of(page("https://example.com/guide", "Java streams support map and filter.")));
    assertEquals("sufficient", assessment.status()); assertTrue(assessment.interpretation().contains("not a verified answer"));
  }
  @Test void copiedAliasesDoNotBecomeIndependentSources() {
    var copied = page("https://one.example/page", "Java streams support map and filter.")
        .withAliases(List.of(new WebSourceAlias("https://two.example/copy", "copy", null)));
    var assessment = WebRetrievalAssessment.assess("compare Java streams sources", List.of(copied));
    assertEquals("insufficient", assessment.status()); assertEquals(1, assessment.independentSources());
    assertTrue(assessment.reasons().contains("independent_sources_missing"));
  }
  @Test void requestedAuthorityAndVersionMustComeFromReadableEvidence() {
    assertTrue(WebRetrievalAssessment.assess("Spring API official", List.of(page("https://blog.example/page", "Spring API example"))).reasons().contains("official_source_missing"));
    assertTrue(WebRetrievalAssessment.assess("Spring 2.0.1 API", List.of(page("https://docs.spring.io/page", "Spring 2.0.0 API example"))).reasons().contains("version_mismatch"));
  }
  @Test void LatestCannotBeVerifiedMerelyFromRetrievalTimeOrAClaimedPublicationDate() {
    var assessment = WebRetrievalAssessment.assess("latest Java release", List.of(page("https://oracle.com/page", "Java release information")));
    assertEquals("unknown", assessment.status()); assertTrue(assessment.reasons().contains("latest_claim_unverified"));
  }
}
