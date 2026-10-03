package dev.mikoto2000.rei.web;
public record FeedResponse(long id, String url, String title, String displayName, boolean enabled) {
  public static FeedResponse from(dev.mikoto2000.rei.feed.Feed f) { return new FeedResponse(f.id(), f.url(), f.title(), f.displayName(), f.enabled()); }
}
