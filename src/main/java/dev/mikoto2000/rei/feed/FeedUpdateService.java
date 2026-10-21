package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** フィード更新のユースケースを実現する。 */
@Service
@RequiredArgsConstructor
public class FeedUpdateService {

  /** フィードサービス。 */
  private final FeedService feedService;

  /** フィード情報の取得元。 */
  private final FeedFetcher feedFetcher;

  /** 指定された変更を、管理中の情報へ反映する。 */
  public FeedUpdateResult update(long feedId) {
    Feed feed = feedService.findById(feedId);
    OffsetDateTime fetchedAt = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      FetchedFeed fetched = feedFetcher.fetch(feed.url());
      feedService.updateFetchedMetadata(
          feedId, fetched.title(), fetched.siteUrl(), fetched.description(), fetchedAt);
      int addedItems = feedService.saveFetchedItems(feedId, fetched.items(), fetchedAt);
      return new FeedUpdateResult(feedId, feedDisplayName(feed, fetched.title()), addedItems, null);
    } catch (FeedFetchException e) {
      feedService.recordFetchFailure(feedId, fetchedAt, e.getMessage(), e.httpStatus());
      return new FeedUpdateResult(feedId, feedDisplayName(feed, null), 0, e.getMessage());
    }
  }

  /** 最新の内容を後続の処理に反映するため、全対象を更新する。 */
  public List<FeedUpdateResult> updateAll() {
    List<FeedUpdateResult> results = new ArrayList<>();
    for (Feed feed : feedService.list()) {
      if (!feed.enabled()) {
        continue;
      }
      results.add(update(feed.id()));
    }
    return results;
  }

  /** フィードの購読と収集で用いるフィード表示名前を返す。 */
  private String feedDisplayName(Feed feed, String fetchedTitle) {
    if (feed.displayName() != null && !feed.displayName().isBlank()) {
      return feed.displayName();
    }
    if (fetchedTitle != null && !fetchedTitle.isBlank()) {
      return fetchedTitle;
    }
    return feed.url();
  }
}
