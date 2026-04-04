package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebPageExtractorTest {

  @Test
  void extractBuildsReadablePageContentFromHtml() {
    WebPageExtractor extractor = new WebPageExtractor();
    WebSearchResult result = new WebSearchResult("Fallback title", "https://example.com/article", "Fallback snippet", null);

    WebSearchPage page = extractor.extract(result, """
        <html>
          <head>
            <title>Spring AI Guide</title>
            <meta property="article:published_time" content="2026-04-01" />
          </head>
          <body>
            <nav>menu</nav>
            <article>
              <h1>Spring AI Guide</h1>
              <p>Spring AI supports tool calling.</p>
              <p>It also supports vector stores.</p>
            </article>
            <script>console.log('ignore');</script>
          </body>
        </html>
        """);

    assertEquals("Spring AI Guide", page.title());
    assertEquals("2026-04-01", page.publishedAt());
    assertTrue(page.content().contains("Spring AI supports tool calling."));
    assertTrue(page.content().contains("It also supports vector stores."));
    assertFalse(page.content().contains("menu"));
    assertFalse(page.content().contains("ignore"));
  }
}
