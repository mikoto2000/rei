package dev.mikoto2000.rei.urlfetch;

import java.net.URI;
import org.springframework.stereotype.Component;

/** URLの内容の参照に必要なURL検証の窓口を扱う。 */
@Component
public class UrlValidator {

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public UrlContentFetchResult validate(String url) {
    if (url == null || url.isBlank()) {
      return UrlContentFetchResult.failure("INPUT_ERROR", "URL must not be blank");
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      return UrlContentFetchResult.failure("INPUT_ERROR", "URL format is invalid");
    }
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      return UrlContentFetchResult.failure("INPUT_ERROR", "URL scheme must be http or https");
    }
    try {
      dev.mikoto2000.rei.http.PublicNetworkPolicy.validateSyntax(uri);
    } catch (dev.mikoto2000.rei.http.HttpFetchException invalid) {
      return UrlContentFetchResult.failure(
          "INPUT_ERROR", "URL format is invalid or contains credentials");
    }
    return UrlContentFetchResult.success(url);
  }
}
