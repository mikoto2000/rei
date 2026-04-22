package dev.mikoto2000.rei.feed;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FeedUpdateService {

  private final FeedService feedService;
  private final FeedFetcher feedFetcher;

  public FeedUpdateResult update(long feedId) {
    Feed feed = feedService.findById(feedId);
    OffsetDateTime fetchedAt = OffsetDateTime.now(ZoneOffset.UTC);
    try {
      FetchedFeed fetched = feedFetcher.fetch(feed.url());
      feedService.updateFetchedMetadata(feedId, fetched.title(), fetched.siteUrl(), fetched.description(), fetchedAt);
      int addedItems = feedService.saveFetchedItems(feedId, fetched.items(), fetchedAt);
      return new FeedUpdateResult(feedId, feedDisplayName(feed, fetched.title()), addedItems, null);
    } catch (FeedFetchException e) {
      feedService.recordFetchFailure(feedId, fetchedAt, e.getMessage(), e.httpStatus());
      return new FeedUpdateResult(feedId, feedDisplayName(feed, null), 0, e.getMessage());
    }
  }

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
