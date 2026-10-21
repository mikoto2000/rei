package dev.mikoto2000.rei.summarize;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import java.net.URI;
import org.springframework.stereotype.Component;

/** URL内容Web内容に必要な情報を外部から取得する。 */
@Component
public class UrlContentWebContentFetcher implements WebContentFetcher {

  /** 取得サービス。 */
  private final UrlContentFetchService fetchService;

  /** 指定された値や利用先を使って、UrlContentWebContentFetcherを初期化する。 */
  public UrlContentWebContentFetcher(UrlContentFetchService fetchService) {
    this.fetchService = fetchService;
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  @Override
  public UrlFetch fetch(URI uri) {
    UrlContentFetchResult result = fetchService.fetch(uri.toString());
    if (result.success()) {
      return UrlFetch.success(result.content());
    }
    return UrlFetch.failure(result.errorType(), result.errorMessage(), result.statusCode());
  }
}
