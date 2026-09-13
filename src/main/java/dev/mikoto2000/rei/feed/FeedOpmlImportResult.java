package dev.mikoto2000.rei.feed;

import java.util.List;

public record FeedOpmlImportResult(List<Feed> imported, List<Entry> skipped, List<Entry> failed) {
  public FeedOpmlImportResult {
    imported = List.copyOf(imported);
    skipped = List.copyOf(skipped);
    failed = List.copyOf(failed);
  }

  public record Entry(OpmlSubscription subscription, String reason) { }
}
