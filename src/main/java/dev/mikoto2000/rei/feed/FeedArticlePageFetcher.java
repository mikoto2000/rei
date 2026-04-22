package dev.mikoto2000.rei.feed;

import dev.mikoto2000.rei.websearch.WebSearchPage;

@FunctionalInterface
public interface FeedArticlePageFetcher {
  WebSearchPage fetch(FeedBriefingItem item) throws Exception;
}
