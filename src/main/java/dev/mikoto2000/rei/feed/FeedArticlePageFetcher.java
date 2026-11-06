package dev.mikoto2000.rei.feed;

import dev.mikoto2000.rei.websearch.WebSearchPage;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;

/** フィード記事ページに必要な情報を外部から取得する。 */
@FunctionalInterface
public interface FeedArticlePageFetcher {
  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      matchType = SuppressMatchType.EXACT,
      justification = "Fetch adapters may report checked failures from their chosen transport or extractor.")
  WebSearchPage fetch(FeedBriefingItem item) throws Exception;
}
