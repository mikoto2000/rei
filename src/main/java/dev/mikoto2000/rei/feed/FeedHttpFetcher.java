package dev.mikoto2000.rei.feed;

import java.net.URI;

@FunctionalInterface
public interface FeedHttpFetcher {
  FeedHttpResponse fetch(URI uri);
}
