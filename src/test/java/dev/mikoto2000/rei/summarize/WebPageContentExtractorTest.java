package dev.mikoto2000.rei.summarize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Webページ内容情報の抽出の結果と状態の契約を検証するテスト。 */
class WebPageContentExtractorTest {

  /** 情報の要約で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void extractsArticleTextWithoutChromeContent() {
    WebPageContentExtractor extractor = new JsoupWebPageContentExtractor();

    String content =
        extractor.extract(
            "https://example.com/article",
            """
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
