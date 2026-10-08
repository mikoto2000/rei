package dev.mikoto2000.rei.urlfetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UrlContentFetchService {

  private static final int DEFAULT_TIMEOUT_SECONDS = 30;
  private static final int CHARSET_SCAN_BYTES = 4096;
  private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\\s\"]+)");

  private final UrlValidator urlValidator;
  private final HttpClient httpClient;
  private final dev.mikoto2000.rei.http.SafeHttpFetcher safeFetcher;
  private final UrlFetchProperties properties;

  public UrlContentFetchService(UrlValidator urlValidator) {
    this(urlValidator, new UrlFetchProperties(), new dev.mikoto2000.rei.http.SafeHttpFetcher());
  }

  @Autowired
  public UrlContentFetchService(UrlValidator validator, UrlFetchProperties properties,
      dev.mikoto2000.rei.http.SafeHttpFetcher fetcher) {
    this.urlValidator = validator; this.properties = properties;
    this.safeFetcher = fetcher; this.httpClient = null;
  }

  UrlContentFetchService(UrlValidator urlValidator, HttpClient httpClient) {
    this.urlValidator = urlValidator;
    this.httpClient = httpClient;
    this.safeFetcher = null;
    this.properties = new UrlFetchProperties();
  }

  public UrlContentFetchResult fetch(String url) {
    return fetch(url, properties.fetchPolicy());
  }

  public UrlContentFetchResult fetch(String url, dev.mikoto2000.rei.http.HttpFetchPolicy policy) {
    long started = System.nanoTime();
    try {
      var result = fetchObserved(url, policy);
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add(
          result.success() ? "fetch_successes" : "fetch_failures", 1);
      return result;
    } finally {
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.duration("fetch", System.nanoTime() - started);
    }
  }

  private UrlContentFetchResult fetchObserved(String url, dev.mikoto2000.rei.http.HttpFetchPolicy policy) {
    UrlContentFetchResult validation = urlValidator.validate(url);
    if (!validation.success()) {
      return validation;
    }

    try {
      if (safeFetcher != null) {
        var response = safeFetcher.fetch(URI.create(url), java.util.Map.of("Accept",
            "text/plain,text/html,application/xhtml+xml,application/json"), policy,
            dev.mikoto2000.rei.http.FetchScope.current(), dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.http(null));
        if (response.status() >= 300) return UrlContentFetchResult.failure("HTTP_ERROR", "HTTP request failed with status: " + response.status(), response.status());
        String rawType = response.header("content-type");
        return UrlContentFetchResult.success(new String(response.body(), charset(rawType, response.body())), normalizeContentType(rawType));
      }
      policy.validate(URI.create(url));
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
          .header("Accept", "text/plain,text/html,application/xhtml+xml,application/json")
          .GET()
          .build();
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add("http_requests", 1);
      HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.status(response.statusCode());
      if (response.body() != null)
        dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add("received_bytes", response.body().length);
      if (response.statusCode() >= 400) {
        return UrlContentFetchResult.failure(
            "HTTP_ERROR",
            "HTTP request failed with status: " + response.statusCode(),
            response.statusCode());
      }
      if (response.body() == null) {
        return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Response body is empty");
      }
      String rawContentType = response.headers() == null ? null : response.headers().firstValue("Content-Type")
          .orElse(null);
      String contentType = normalizeContentType(rawContentType);
      Charset charset = charset(rawContentType, response.body());
      return UrlContentFetchResult.success(new String(response.body(), charset), contentType);
    } catch (IOException e) {
      if (e instanceof java.net.http.HttpTimeoutException)
        dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add("timeouts", 1);
      return UrlContentFetchResult.failure("NETWORK_ERROR", "Network error");
    } catch (InterruptedException e) {
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add("cancellations", 1);
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    } catch (RuntimeException e) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(e);
      if (e instanceof dev.mikoto2000.rei.http.HttpFetchException safe)
        return UrlContentFetchResult.failure(safe.code().name(), safe.code().name());
      return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Failed to fetch URL content");
    }
  }

  private String normalizeContentType(String rawContentType) {
    if (rawContentType == null || rawContentType.isBlank()) {
      return null;
    }
    return rawContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }

  private Charset charset(String rawContentType, byte[] body) {
    return charsetFromContentType(rawContentType)
        .or(() -> charsetFromHtml(body))
        .orElse(StandardCharsets.UTF_8);
  }

  private Optional<Charset> charsetFromContentType(String rawContentType) {
    if (rawContentType == null || rawContentType.isBlank()) {
      return Optional.empty();
    }
    Matcher matcher = CONTENT_TYPE_CHARSET.matcher(rawContentType);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return charsetByName(matcher.group(1));
  }

  private Optional<Charset> charsetFromHtml(byte[] body) {
    if (body == null || body.length == 0) {
      return Optional.empty();
    }
    String head = new String(body, 0, Math.min(body.length, CHARSET_SCAN_BYTES), StandardCharsets.ISO_8859_1);
    // Parse attributes rather than matching markup inside comments or scripts.
    for (Element meta : Jsoup.parse(head).select("meta")) {
      Optional<Charset> charset = charsetByName(meta.attr("charset"));
      if (charset.isEmpty() && "content-type".equalsIgnoreCase(meta.attr("http-equiv").trim())) {
        charset = charsetFromContentType(meta.attr("content"));
      }
      if (charset.isPresent()) {
        return charset;
      }
    }
    return Optional.empty();
  }

  private Optional<Charset> charsetByName(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Charset.forName(name.trim()));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }
}
