package dev.mikoto2000.rei.feed;

import java.util.function.Function;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.websearch.WebPageExtractor;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import dev.mikoto2000.rei.websearch.WebSearchResult;

@Component
public class DefaultFeedArticlePageFetcher implements Function<FeedBriefingItem, WebSearchPage> {

  private final UrlContentFetchService urlContentFetchService;
  private final WebPageExtractor webPageExtractor;

  public DefaultFeedArticlePageFetcher(UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor) {
    this.urlContentFetchService = urlContentFetchService;
    this.webPageExtractor = webPageExtractor;
  }

  @Override
  public WebSearchPage apply(FeedBriefingItem item) {
    try {
      UrlContentFetchResult fetched = urlContentFetchService.fetch(item.url());
      if (!fetched.success()) {
        throw new IllegalStateException(fetched.errorType() + ": " + fetched.errorMessage());
      }
      return webPageExtractor.extract(new WebSearchResult(item.title(), item.url(), "",
          item.publishedAt() == null ? null : item.publishedAt().toString()), fetched.content());
    } catch (Exception e) {
      throw new IllegalStateException("記事本文の取得に失敗しました: " + item.url(), e);
    }
  }
}
