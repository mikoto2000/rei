package dev.mikoto2000.rei.websearch;

import java.io.IOException;

import org.springframework.stereotype.Component;

@Component
public class WebPageFetcher {

  private final WebSearchProperties properties;
  private final WebPageExtractor extractor;
  private final dev.mikoto2000.rei.urlfetch.UrlContentFetchService fetcher;

  public WebPageFetcher(WebSearchProperties properties, WebPageExtractor extractor) {
    this(properties, extractor, new dev.mikoto2000.rei.urlfetch.UrlContentFetchService(new dev.mikoto2000.rei.urlfetch.UrlValidator()));
  }
  @org.springframework.beans.factory.annotation.Autowired
  public WebPageFetcher(WebSearchProperties properties, WebPageExtractor extractor,
      dev.mikoto2000.rei.urlfetch.UrlContentFetchService fetcher) {
    this.properties = properties;
    this.extractor = extractor;
    this.fetcher = fetcher;
  }

  public WebSearchPage fetch(WebSearchResult result) throws IOException, InterruptedException {
    var response = fetcher.fetch(result.url(), properties.fetchPolicy());
    if (!response.success()) {
      dev.mikoto2000.rei.http.HttpFetchException.Code code;
      try { code = dev.mikoto2000.rei.http.HttpFetchException.Code.valueOf(response.errorType()); }
      catch (IllegalArgumentException | NullPointerException unknown) { code = dev.mikoto2000.rei.http.HttpFetchException.Code.NETWORK_ERROR; }
      throw new dev.mikoto2000.rei.http.HttpFetchException(code);
    }
    var extracted = extractor.extract(result, response.content());
    var page = extracted.withEvidence(response.retrievedAt(), response.validatedAt(), extracted.excerpts(), extracted.omissions());
    if (response.finalUrl() != null && !response.finalUrl().equals(result.url()))
      page = page.withAliases(java.util.List.of(new WebSourceAlias(response.finalUrl(), page.title(), page.publishedAt(), "http_redirect")));
    return page;
  }
}
