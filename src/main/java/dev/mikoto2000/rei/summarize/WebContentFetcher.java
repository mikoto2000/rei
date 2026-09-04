package dev.mikoto2000.rei.summarize;

import java.net.URI;

public interface WebContentFetcher {

  UrlFetch fetch(URI uri);
}
