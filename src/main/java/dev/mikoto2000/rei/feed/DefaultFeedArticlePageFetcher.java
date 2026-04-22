package dev.mikoto2000.rei.feed;

import java.util.function.Function;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.websearch.WebPageFetcher;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import dev.mikoto2000.rei.websearch.WebSearchResult;

@Component
public class DefaultFeedArticlePageFetcher implements Function<FeedBriefingItem, WebSearchPage> {

  private final WebPageFetcher webPageFetcher;

  public DefaultFeedArticlePageFetcher(WebPageFetcher webPageFetcher) {
    this.webPageFetcher = webPageFetcher;
  }

  @Override
  public WebSearchPage apply(FeedBriefingItem item) {
    try {
      return webPageFetcher.fetch(new WebSearchResult(
          item.title(),
          item.url(),
          "",
          item.publishedAt() == null ? null : item.publishedAt().toString()));
    } catch (Exception e) {
      throw new IllegalStateException("記事本文の取得に失敗しました: " + item.url(), e);
    }
  }
}
