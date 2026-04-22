package dev.mikoto2000.rei.feed;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.websearch.WebPageFetcher;
import dev.mikoto2000.rei.websearch.WebSearchResult;

@Component
public class DefaultFeedArticlePageFetcher implements FeedArticlePageFetcher {

  private final WebPageFetcher webPageFetcher;

  public DefaultFeedArticlePageFetcher(WebPageFetcher webPageFetcher) {
    this.webPageFetcher = webPageFetcher;
  }

  @Override
  public dev.mikoto2000.rei.websearch.WebSearchPage fetch(FeedBriefingItem item) throws Exception {
    return webPageFetcher.fetch(new WebSearchResult(
        item.title(),
        item.url(),
        "",
        item.publishedAt() == null ? null : item.publishedAt().toString()));
  }
}
