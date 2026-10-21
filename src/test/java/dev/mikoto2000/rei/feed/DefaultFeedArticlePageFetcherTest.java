package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.websearch.WebPageExtractor;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 既定フィード記事ページ情報の取得元の結果と状態の契約を検証するテスト。 */
class DefaultFeedArticlePageFetcherTest {

  /** 共有URL情報の取得元を再利用することを確認する。 */
  @Test
  void reusesSharedUrlFetcherAndReadableTextExtractor() {
    UrlContentFetchService fetchService = Mockito.mock(UrlContentFetchService.class);
    WebPageExtractor extractor = new WebPageExtractor();
    when(fetchService.fetch("https://example.com/article"))
        .thenReturn(
            UrlContentFetchResult.success(
                "<html><body><main>ARTICLE MARKER</main></body></html>", "text/html"));
    DefaultFeedArticlePageFetcher fetcher =
        new DefaultFeedArticlePageFetcher(fetchService, extractor);

    var page = fetcher.apply(item("https://example.com/article"));

    assertEquals("ARTICLE MARKER", page.content());
    verify(fetchService).fetch("https://example.com/article");
  }

  /** フィードの購読と収集で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void turnsSharedFetcherFailureIntoFallbackSignal() {
    UrlContentFetchService fetchService = Mockito.mock(UrlContentFetchService.class);
    when(fetchService.fetch("http://127.0.0.1/private"))
        .thenReturn(UrlContentFetchResult.failure("SSRF_BLOCKED", "private address"));
    DefaultFeedArticlePageFetcher fetcher =
        new DefaultFeedArticlePageFetcher(fetchService, new WebPageExtractor());

    assertThrows(
        IllegalStateException.class, () -> fetcher.apply(item("http://127.0.0.1/private")));
  }

  /** {@code item} の条件を再現するための補助情報を返す。 */
  private FeedBriefingItem item(String url) {
    return new FeedBriefingItem(
        1L, "Title", url, OffsetDateTime.parse("2026-04-22T01:23:45Z"), "Feed");
  }
}
