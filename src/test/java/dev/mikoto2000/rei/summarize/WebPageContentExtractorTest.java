package dev.mikoto2000.rei.summarize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class WebPageContentExtractorTest {

  @Test
  void extractsArticleTextWithoutChromeContent() {
    WebPageContentExtractor extractor = new JsoupWebPageContentExtractor();

    String content = extractor.extract("https://example.com/article", """
        <html>
          <head>
            <script>console.log('ignore')</script>
            <style>body { color: red; }</style>
          </head>
          <body>
            <header>site header</header>
            <nav>menu</nav>
            <main>
              <article>
                Important article content
              </article>
            </main>
            <footer>footer</footer>
            <noscript>noscript fallback</noscript>
            <iframe src="ad.html"></iframe>
          </body>
        </html>
        """);

    assertEquals("Important article content", content);
    assertFalse(content.contains("menu"));
    assertFalse(content.contains("footer"));
    assertFalse(content.contains("ignore"));
    assertFalse(content.contains("noscript"));
  }
}
