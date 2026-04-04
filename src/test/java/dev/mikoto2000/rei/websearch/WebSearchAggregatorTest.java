package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class WebSearchAggregatorTest {

  @Test
  void aggregatePromotesOfficialAndDetailedPagesToPrimary() {
    WebSearchAggregator aggregator = new WebSearchAggregator();

    WebSearchPage official = new WebSearchPage(
        "Spring AI Docs",
        "https://docs.spring.io/spring-ai/reference/index.html",
        "Docs snippet",
        "2026-04-01",
        "Detailed official documentation content about Spring AI tool calling and vector stores.");
    WebSearchPage blog = new WebSearchPage(
        "Blog Post",
        "https://example.com/blog/spring-ai",
        "Blog snippet",
        null,
        "Short note");

    WebSearchContext context = aggregator.aggregate(List.of(blog, official), 5);

    assertEquals(List.of("https://docs.spring.io/spring-ai/reference/index.html"),
        context.primaryResults().stream().map(WebSearchPage::url).toList());
    assertEquals(List.of("https://example.com/blog/spring-ai"),
        context.secondaryResults().stream().map(WebSearchPage::url).toList());
  }

  @Test
  void aggregateKeepsTopRankedPagesWithinLimit() {
    WebSearchAggregator aggregator = new WebSearchAggregator();

    List<WebSearchPage> pages = List.of(
        new WebSearchPage("Official", "https://docs.example.com/a", "s", "2026-04-01", "Detailed content one."),
        new WebSearchPage("Developer", "https://developer.example.com/b", "s", "2026-04-02", "Detailed content two."),
        new WebSearchPage("Blog", "https://example.com/c", "s", null, "Small"));

    WebSearchContext context = aggregator.aggregate(pages, 2);

    assertEquals(2, context.allResults().size());
    assertTrue(context.allResults().stream().noneMatch(page -> page.url().equals("https://example.com/c")));
  }
}
