package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;

/** Frozen HTML, no live providers, no model calls, cold extraction with identical source URLs. */
class WebExtractionQualityComparisonTest {
  record Fixture(String query,String html,String evidence) { }
  static List<Fixture> fixtures() {
    String introduction="<p>"+"unrelated introduction and navigation ".repeat(100)+"</p>";
    return List.of(
        new Fixture("alpha","<h1>Alpha</h1><p>alpha evidence-first</p>","evidence-first"),
        new Fixture("beta",introduction+"<h2>Beta</h2><p>beta evidence-middle</p>"+introduction,"evidence-middle"),
        new Fixture("gamma",introduction+introduction+"<p>gamma evidence-last</p>","evidence-last"),
        new Fixture("createResponse",introduction+"<h2>createResponse</h2><pre>if (ready) {\n  createResponse(); // evidence-code\n}</pre>","evidence-code"),
        new Fixture("Client 2.0.1",introduction+"<h2>Client 2.0.1</h2><p>Client 2.0.1 evidence-version</p>","evidence-version"),
        new Fixture("日本語 設定",introduction+"<h2>日本語設定</h2><p>日本語 設定 evidence-japanese</p>","evidence-japanese"),
        new Fixture("delta",introduction+"<ul><li>delta evidence-list<ul><li>delta nested option</li></ul></li></ul>","evidence-list"),
        new Fixture("epsilon",introduction+"<table><tr><th>epsilon</th><th>value</th></tr><tr><td>epsilon mode</td><td>evidence-table</td></tr></table>","evidence-table"),
        new Fixture("rare_api",introduction+"<pre>rare_api(); // evidence-rare</pre>","evidence-rare"),
        new Fixture("omega theta",introduction+"<p>omega evidence-omega</p>"+introduction+"<p>theta evidence-theta</p>","evidence-omega|evidence-theta"));
  }
  @Test void relevantEvidenceAndExactUrlRecallDoNotRegressAgainstLegacyPrefix() {
    var extractor=new WebPageExtractor();var budget=WebResultBudget.defaults();
    int beforeCoverage=0,afterCoverage=0,beforeTokens=0,afterTokens=0,urlRecall=0,index=0;
    for(var fixture:fixtures()) {
      String url="https://example.com/fixture/"+index++;var source=new WebSearchResult(fixture.query(),url,"",null);
      var before=extractor.extract(source,fixture.html());
      var after=extractor.extract(source,fixture.html(),fixture.query());
      var fitted=budget.fit(new WebSearchAndReadResponse(fixture.query(),List.of(WebSearchAndReadItem.fromPage(after,"text/html",null))),null);
      assertEquals(1,fitted.results().size());var actual=fitted.results().getFirst();
      var evidence=fixture.evidence().split("\\|");
      if(Arrays.stream(evidence).allMatch(before.content()::contains))beforeCoverage++;
      if(Arrays.stream(evidence).allMatch(actual.content()::contains))afterCoverage++;
      if(actual.url().equals(url))urlRecall++;
      beforeTokens+=TokenEstimator.conservative().text(budget.encoded(new WebSearchAndReadResponse(fixture.query(),List.of(WebSearchAndReadItem.fromPage(before,"text/html",null)))));
      afterTokens+=TokenEstimator.conservative().text(budget.encoded(fitted));
      assertTrue(Arrays.stream(evidence).allMatch(actual.content()::contains),fixture.query());
    }
    assertEquals(10,afterCoverage);assertEquals(10,urlRecall);assertTrue(afterCoverage>=beforeCoverage);
    assertTrue(afterTokens<beforeTokens,"same converter includes citation metadata in both totals");
    System.out.printf(Locale.ROOT,"PHASE5_FIXED_HTML pages=10 prefixEvidence=%d relevantEvidence=%d exactUrlRecall=%d prefixEstimatedTokens=%d relevantEstimatedTokens=%d%n",
        beforeCoverage,afterCoverage,urlRecall,beforeTokens,afterTokens);
  }
}
