package dev.mikoto2000.rei.summarize;

import java.net.URI;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;

@Component
public class UrlContentWebContentFetcher implements WebContentFetcher {

  private final UrlContentFetchService fetchService;

  public UrlContentWebContentFetcher(UrlContentFetchService fetchService) {
    this.fetchService = fetchService;
  }

  @Override
  public UrlFetch fetch(URI uri) {
    UrlContentFetchResult result = fetchService.fetch(uri.toString());
    if (result.success()) {
      return UrlFetch.success(result.content());
    }
    return UrlFetch.failure(result.errorType(), result.errorMessage(), result.statusCode());
  }
}
