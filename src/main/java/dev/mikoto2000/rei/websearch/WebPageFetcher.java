package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;
import java.io.IOException;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Webページに必要な情報を外部から取得する。 */
@Component
public class WebPageFetcher {

  /** 情報の抽出。 */
  private final WebPageExtractor extractor;

  /** 情報の取得元。 */
  private final Function<String, UrlContentFetchResult> fetchContent;

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebPageFetcher(WebSearchProperties properties, WebPageExtractor extractor) {
    this(
        properties,
        extractor,
        new dev.mikoto2000.rei.urlfetch.UrlContentFetchService(
            new dev.mikoto2000.rei.urlfetch.UrlValidator()));
  }

  /** 指定された値や利用先を使って、WebPageFetcherを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WebPageFetcher(
      WebSearchProperties properties,
      WebPageExtractor extractor,
      dev.mikoto2000.rei.urlfetch.UrlContentFetchService fetcher) {
    this.extractor = extractor;
    this.fetchContent = url -> fetcher.fetch(url, properties.fetchPolicy());
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  public WebSearchPage fetch(WebSearchResult result) throws IOException, InterruptedException {
    var response = fetchContent.apply(result.url());
    if (!response.success()) {
      var code = dev.mikoto2000.rei.http.HttpFetchException.Code.NETWORK_ERROR;
      var errorType = response.errorType();
      if (errorType != null) {
        try {
          code = dev.mikoto2000.rei.http.HttpFetchException.Code.valueOf(errorType);
        } catch (IllegalArgumentException unknown) {
          // Unrecognized fetch errors use the default network error.
        }
      }
      throw new dev.mikoto2000.rei.http.HttpFetchException(code);
    }
    var extracted = extractor.extract(result, response.content());
    var page =
        extracted.withEvidence(
            response.retrievedAt(),
            response.validatedAt(),
            extracted.excerpts(),
            extracted.omissions());
    if (response.finalUrl() != null && !response.finalUrl().equals(result.url()))
      page =
          page.withAliases(
              java.util.List.of(
                  new WebSourceAlias(
                      response.finalUrl(), page.title(), page.publishedAt(), "http_redirect")));
    return page;
  }
}
