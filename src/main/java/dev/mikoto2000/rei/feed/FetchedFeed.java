package dev.mikoto2000.rei.feed;

import java.util.List;

public record FetchedFeed(
    String title,
    String siteUrl,
    String description,
    List<FetchedFeedItem> items) {
}
