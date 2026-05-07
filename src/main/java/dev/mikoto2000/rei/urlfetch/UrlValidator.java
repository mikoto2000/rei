package dev.mikoto2000.rei.urlfetch;

import java.net.URI;

import org.springframework.stereotype.Component;

@Component
public class UrlValidator {

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
    return UrlContentFetchResult.success(url);
  }
}
