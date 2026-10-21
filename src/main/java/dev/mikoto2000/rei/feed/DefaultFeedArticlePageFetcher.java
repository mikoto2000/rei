package dev.mikoto2000.rei.feed;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.websearch.WebPageExtractor;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import dev.mikoto2000.rei.websearch.WebSearchResult;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** 既定フィード記事ページに必要な情報を外部から取得する。 */
@Component
public class DefaultFeedArticlePageFetcher implements Function<FeedBriefingItem, WebSearchPage> {

  /** URL内容取得サービス。 */
  private final UrlContentFetchService urlContentFetchService;

  /** Webページ情報の抽出。 */
  private final WebPageExtractor webPageExtractor;

  /** 指定された値や利用先を使って、DefaultFeedArticlePageFetcherを初期化する。 */
  public DefaultFeedArticlePageFetcher(
      UrlContentFetchService urlContentFetchService, WebPageExtractor webPageExtractor) {
    this.urlContentFetchService = urlContentFetchService;
    this.webPageExtractor = webPageExtractor;
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  @Override
  public WebSearchPage apply(FeedBriefingItem item) {
    try {
      UrlContentFetchResult fetched = urlContentFetchService.fetch(item.url());
      if (!fetched.success()) {
        throw new IllegalStateException(fetched.errorType() + ": " + fetched.errorMessage());
      }
      return webPageExtractor.extract(
          new WebSearchResult(
              item.title(),
              item.url(),
              "",
              item.publishedAt() == null ? null : item.publishedAt().toString()),
          fetched.content());
    } catch (Exception e) {
      throw new IllegalStateException("記事本文の取得に失敗しました: " + item.url(), e);
    }
  }
}
