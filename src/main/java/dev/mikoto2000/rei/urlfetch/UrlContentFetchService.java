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

/** URL内容取得のユースケースを実現する。 */
@Service
public class UrlContentFetchService {

  /** 既定タイムアウトの秒数。 */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /** 文字コード調査バイト数。 */
  private static final int CHARSET_SCAN_BYTES = 4096;

  /** 内容の種類文字コード。 */
  private static final Pattern CONTENT_TYPE_CHARSET =
      Pattern.compile("(?i)(?:^|;)\\s*charset\\s*=\\s*\"?([^;\\s\"]+)");

  /** URL検証の窓口。 */
  private final UrlValidator urlValidator;

  /** 外部情報を取得するHTTP通信の窓口。 */
  private final HttpClient httpClient;

  /** 安全性情報の取得元。 */
  private final dev.mikoto2000.rei.http.SafeHttpFetcher safeFetcher;

  /** この機能の動作設定。 */
  private final UrlFetchProperties properties;

  /** キャッシュ。 */
  private final dev.mikoto2000.rei.http.cache.HttpResponseCache cache;

  /** URLの内容の参照を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UrlContentFetchService(UrlValidator urlValidator) {
    this(urlValidator, new UrlFetchProperties(), new dev.mikoto2000.rei.http.SafeHttpFetcher());
  }

  /** URLの内容の参照を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UrlContentFetchService(
      UrlValidator validator,
      UrlFetchProperties properties,
      dev.mikoto2000.rei.http.SafeHttpFetcher fetcher) {
    this(
        validator, properties, fetcher, dev.mikoto2000.rei.http.cache.HttpResponseCache.disabled());
  }

  /** 指定された値や利用先を使って、UrlContentFetchServiceを初期化する。 */
  @Autowired
  public UrlContentFetchService(
      UrlValidator validator,
      UrlFetchProperties properties,
      dev.mikoto2000.rei.http.SafeHttpFetcher fetcher,
      dev.mikoto2000.rei.http.cache.HttpResponseCache cache) {
    this.urlValidator = validator;
    this.properties = properties;
    this.safeFetcher = fetcher;
    this.httpClient = null;
    this.cache = cache;
  }

  /** 指定された値や利用先を使って、UrlContentFetchServiceを初期化する。 */
  UrlContentFetchService(UrlValidator urlValidator, HttpClient httpClient) {
    this.urlValidator = urlValidator;
    this.httpClient = httpClient;
    this.safeFetcher = null;
    this.properties = new UrlFetchProperties();
    this.cache = dev.mikoto2000.rei.http.cache.HttpResponseCache.disabled();
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  public UrlContentFetchResult fetch(String url) {
    return fetch(url, properties.fetchPolicy());
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  public UrlContentFetchResult fetch(String url, dev.mikoto2000.rei.http.HttpFetchPolicy policy) {
    long started = System.nanoTime();
    try {
      var result = fetchObserved(url, policy);
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add(
          result.success() ? "fetch_successes" : "fetch_failures", 1);
      return result;
    } finally {
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.duration(
          "fetch", System.nanoTime() - started);
    }
  }

  /** 外部の情報を利用できるよう、観測を取得する。 */
  private UrlContentFetchResult fetchObserved(
      String url, dev.mikoto2000.rei.http.HttpFetchPolicy policy) {
    UrlContentFetchResult validation = urlValidator.validate(url);
    if (!validation.success()) {
      return validation;
    }

    try {
      if (safeFetcher != null) {
        var response =
            cache.fetch(
                new dev.mikoto2000.rei.http.cache.HttpResponseCache.Request(
                    URI.create(url),
                    java.util.Map.of(
                        "Accept", "text/plain,text/html,application/xhtml+xml,application/json"),
                    policy,
                    dev.mikoto2000.rei.http.cache.HttpResponseCache.Namespace.PAGE,
                    dev.mikoto2000.rei.http.FetchScope.forceRefresh()),
                dev.mikoto2000.rei.http.FetchScope.current(),
                dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.http(null),
                safeFetcher);
        if (response.status() >= 300)
          return UrlContentFetchResult.failure(
              "HTTP_ERROR",
              "HTTP request failed with status: " + response.status(),
              response.status());
        String rawType = response.header("content-type");
        return UrlContentFetchResult.success(
            new String(response.body(), charset(rawType, response.body())),
            normalizeContentType(rawType),
            response.finalUri().toString(),
            response.retrievedAt(),
            response.validatedAt());
      }
      policy.validate(URI.create(url));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS))
              .header("Accept", "text/plain,text/html,application/xhtml+xml,application/json")
              .GET()
              .build();
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add("http_requests", 1);
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.status(response.statusCode());
      if (response.body() != null)
        dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add(
            "received_bytes", response.body().length);
      if (response.statusCode() >= 400) {
        return UrlContentFetchResult.failure(
            "HTTP_ERROR",
            "HTTP request failed with status: " + response.statusCode(),
            response.statusCode());
      }
      if (response.body() == null) {
        return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Response body is empty");
      }
      String rawContentType =
          response.headers() == null
              ? null
              : response.headers().firstValue("Content-Type").orElse(null);
      String contentType = normalizeContentType(rawContentType);
      Charset charset = charset(rawContentType, response.body());
      return UrlContentFetchResult.success(new String(response.body(), charset), contentType);
    } catch (IOException e) {
      if (e instanceof java.net.http.HttpTimeoutException)
        dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add("timeouts", 1);
      return UrlContentFetchResult.failure("NETWORK_ERROR", "Network error");
    } catch (InterruptedException e) {
      dev.mikoto2000.rei.websearch.WebSearchMetrics.OBSERVED.add("cancellations", 1);
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new java.util.concurrent.CancellationException();
    } catch (RuntimeException e) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(e);
      if (e instanceof dev.mikoto2000.rei.http.HttpFetchException safe)
        return UrlContentFetchResult.failure(safe.code().name(), safe.code().name());
      return UrlContentFetchResult.failure("EXTRACTION_ERROR", "Failed to fetch URL content");
    }
  }

  /** 同じ意味の指定を共通に扱えるよう、内容の種類の表記をそろえる。 */
  private String normalizeContentType(String rawContentType) {
    if (rawContentType == null || rawContentType.isBlank()) {
      return null;
    }
    return rawContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
  }

  /** URLの内容の参照で用いる文字コードを返す。 */
  private Charset charset(String rawContentType, byte[] body) {
    return charsetFromContentType(rawContentType)
        .or(() -> charsetFromHtml(body))
        .orElse(StandardCharsets.UTF_8);
  }

  /** URLの内容の参照で用いる文字コード起点内容の種類を返す。 */
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

  /** URLの内容の参照で用いる文字コード起点HTMLを返す。 */
  private Optional<Charset> charsetFromHtml(byte[] body) {
    if (body == null || body.length == 0) {
      return Optional.empty();
    }
    String head =
        new String(body, 0, Math.min(body.length, CHARSET_SCAN_BYTES), StandardCharsets.ISO_8859_1);
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

  /** URLの内容の参照で用いる文字コード別名前を返す。 */
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
