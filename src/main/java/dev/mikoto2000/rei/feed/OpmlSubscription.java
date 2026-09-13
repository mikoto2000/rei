package dev.mikoto2000.rei.feed;

import java.util.List;

/** Raw OPML metadata; invalid URLs are retained for per-subscription reporting. */
public record OpmlSubscription(String title, String text, String xmlUrl, String htmlUrl, List<String> categories) {
  public OpmlSubscription {
    categories = List.copyOf(categories);
  }

  public String displayName() {
    return !title.isBlank() ? title : !text.isBlank() ? text : xmlUrl;
  }
}
