package dev.mikoto2000.rei.feed;

import dev.mikoto2000.rei.websearch.WebSearchPage;

/** フィード記事ページに必要な情報を外部から取得する。 */
@FunctionalInterface
public interface FeedArticlePageFetcher {
  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  WebSearchPage fetch(FeedBriefingItem item) throws Exception;
}
