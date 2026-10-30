package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.websearch.WebSearchProperties.ProviderProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Web検索のユースケースを実現する。 */
@Service
public class WebSearchService {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

  /** この機能の動作設定。 */
  private final WebSearchProperties properties;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper objectMapper;

  /** 情報の取得元。 */
  private final dev.mikoto2000.rei.http.SafeHttpFetcher fetcher;

  /** キャッシュ。 */
  private final dev.mikoto2000.rei.http.cache.HttpResponseCache cache;

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchService(WebSearchProperties properties, JsonMapper objectMapper) {
    this(properties, objectMapper, new dev.mikoto2000.rei.http.SafeHttpFetcher());
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchService(
      WebSearchProperties properties,
      JsonMapper objectMapper,
      dev.mikoto2000.rei.http.SafeHttpFetcher fetcher) {
    this(
        properties,
        objectMapper,
        fetcher,
        dev.mikoto2000.rei.http.cache.HttpResponseCache.disabled());
  }

  /** 指定された値や利用先を使って、WebSearchServiceを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchService(
      WebSearchProperties properties,
      JsonMapper objectMapper,
      dev.mikoto2000.rei.http.SafeHttpFetcher fetcher,
      dev.mikoto2000.rei.http.cache.HttpResponseCache cache) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.fetcher = fetcher;
    this.cache = cache;
  }

  /** 鮮度の要求と検索回数の予算を適用し、検索結果の候補を集める。 */
  public List<WebSearchResult> search(String query, Integer limit)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    properties.validateSelection();
    try (var refresh =
            dev.mikoto2000.rei.http.FetchScope.withForceRefresh(
                query != null && WebSearchSelection.needsFreshness(query));
        var research = WebResearchScope.enter(properties);
        var budget = SearchRequestBudget.enter(properties.getMaxSearchApiCalls())) {
      return searchObserved(query, limit);
    } finally {
      WebSearchMetrics.OBSERVED.duration("search", System.nanoTime() - started);
    }
  }

  /** 設定済みの検索先を順に利用し、URLの重複と情報の充足度を考慮して候補を集める。 */
  private List<WebSearchResult> searchObserved(String query, Integer limit)
      throws IOException, InterruptedException {
    if (!properties.isEnabled()) {
      throw new IllegalStateException(
          "Web search is disabled. Set REI_WEB_SEARCH_ENABLED=true to enable it.");
    }

    int requestedLimit = limit == null ? properties.getMaxResults() : limit;
    // 利用者の希望件数を、設定された検索結果の上限に収める。
    int clampedLimit = Math.max(1, Math.min(requestedLimit, properties.getMaxResults()));
    List<ProviderProperties> providers = configuredProviders();
    Map<String, WebSearchResult> resultsByUrl = new LinkedHashMap<>();
    Exception firstError = null;

    // 検索回数の予算を守りながら複数の検索先を利用し、必要な情報が集まれば追加検索を終える。
    for (ProviderProperties provider : providers) {
      if (SearchRequestBudget.exhausted()) break;
      dev.mikoto2000.rei.http.FetchScope.current().check();
      try {
        for (WebSearchResult result : searchWithProvider(provider, query, clampedLimit)) {
          WebSearchMetrics.OBSERVED.add("results", 1);
          if (resultsByUrl.containsKey(result.url()))
            WebSearchMetrics.OBSERVED.add("duplicate_urls", 1);
          // 同じURLの候補は重ねて返さず、検索目的に対する優先度が高い情報を残す。
          resultsByUrl.merge(
              result.url(),
              result,
              (existing, replacement) ->
                  WebSearchSelection.priority(query, replacement)
                          > WebSearchSelection.priority(query, existing)
                      ? replacement
                      : existing);
        }
        if (WebSearchSelection.sufficient(
            query, new ArrayList<>(resultsByUrl.values()), clampedLimit)) break;
      } catch (IOException | InterruptedException | RuntimeException e) {
        // 中断や取得制限は継続させず、通常の検索先の障害だけを次の候補で補う。
        dev.mikoto2000.rei.http.FetchOperation.propagateControls(e);
        log.warn(
            "Web search provider failed; provider={}, causeType={}, code={}, httpStatus={}",
            providerLogName(provider),
            e.getClass().getSimpleName(),
            e instanceof WebSearchHttpException
                ? "HTTP_ERROR"
                : e instanceof dev.mikoto2000.rei.http.HttpFetchException http
                    ? http.code().name()
                    : "PROVIDER_ERROR",
            e instanceof WebSearchHttpException http ? http.status() : null);
        if (firstError == null) {
          firstError = e;
        }
      }
    }

    // 一部の検索先が失敗しても、得られた候補があれば調査を継続できるようにする。
    if (!resultsByUrl.isEmpty()) {
      return new ArrayList<>(resultsByUrl.values());
    }
    if (firstError instanceof IOException ioException) {
      throw ioException;
    }
    if (firstError instanceof InterruptedException interruptedException) {
      throw interruptedException;
    }
    if (firstError instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    return List.of();
  }

  /** 有効な検索先の設定を取り出し、検索先がない場合は設定の問題を伝える。 */
  List<ProviderProperties> configuredProviders() {
    if (properties.getProviders() == null || properties.getProviders().isEmpty()) {
      String detail = "enabled=%s, providersCount=0".formatted(properties.isEnabled());
      log.warn("Web search providers were empty at execution time: {}", detail);
      throw new IllegalStateException("No web search providers are configured. " + detail);
    }

    List<ProviderProperties> providers = new ArrayList<>();
    for (ProviderProperties provider : properties.getProviders()) {
      if (provider == null || provider.getName() == null || provider.getName().isBlank()) {
        continue;
      }
      providers.add(provider);
    }
    if (providers.isEmpty()) {
      String detail =
          "enabled=%s, providersCount=%s"
              .formatted(properties.isEnabled(), properties.getProviders().size());
      log.warn("Web search providers were present but all invalid at execution time: {}", detail);
      throw new IllegalStateException("No web search providers are configured. " + detail);
    }
    return providers;
  }

  /** 依頼に関連する情報を得るため、付き提供元を検索する。 */
  private List<WebSearchResult> searchWithProvider(
      ProviderProperties provider, String query, int limit)
      throws IOException, InterruptedException {
    String providerName = provider.getName().trim().toLowerCase();
    return switch (providerName) {
      case "duckduckgo" -> searchDuckDuckGo(provider, query, limit);
      case "brave" -> searchBrave(provider, query, limit);
      default ->
          throw new IllegalStateException("Unsupported web search provider: " + provider.getName());
    };
  }

  /** 依頼に関連する情報を得るため、Braveを検索する。 */
  private List<WebSearchResult> searchBrave(ProviderProperties provider, String query, int limit)
      throws IOException, InterruptedException {
    if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
      throw new IllegalStateException("Web search API key is not configured for provider brave.");
    }

    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create(provider.getBaseUrl() + "?q=" + encodedQuery + "&count=" + limit);

    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
            .header("Accept", "application/json")
            .header("X-Subscription-Token", provider.getApiKey())
            .GET()
            .build();

    var response = send(request, provider, limit);
    if (response.status() >= 400) {
      throw new WebSearchHttpException(response.status());
    }

    return observeResults(
        provider,
        response.status(),
        parseBraveResults(new String(response.body(), StandardCharsets.UTF_8), limit));
  }

  /** 依頼に関連する情報を得るため、DuckDuckGoを検索する。 */
  private List<WebSearchResult> searchDuckDuckGo(
      ProviderProperties provider, String query, int limit)
      throws IOException, InterruptedException {
    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    URI uri = URI.create(provider.getBaseUrl() + "?q=" + encodedQuery);

    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
            .header("Accept", "text/html")
            .header("User-Agent", "Rei/0.0.1")
            .GET()
            .build();

    var response = send(request, provider, limit);
    if (response.status() >= 400) {
      throw new WebSearchHttpException(response.status());
    }

    return observeResults(
        provider,
        response.status(),
        parseDuckDuckGoResults(new String(response.body(), StandardCharsets.UTF_8), limit));
  }

  /** 空の結果とHTTP障害を区別し、本文を残さずに検索先の状態を記録する。 */
  private List<WebSearchResult> observeResults(
      ProviderProperties provider, int status, List<WebSearchResult> results) {
    if (results.isEmpty()) {
      log.info(
          "Web search provider returned no results; provider={}, code=NO_RESULTS, httpStatus={}",
          providerLogName(provider),
          status);
    }
    return results;
  }

  /** 設定に含まれる任意の文字列をログへ出さず、対応する検索先だけを識別する。 */
  private String providerLogName(ProviderProperties provider) {
    String name = provider.getName().trim().toLowerCase(java.util.Locale.ROOT);
    return switch (name) {
      case "brave", "duckduckgo" -> name;
      default -> "unsupported";
    };
  }

  /** 入力を機能内で扱えるよう、Brave結果一覧を解釈する。 */
  List<WebSearchResult> parseBraveResults(String responseBody, int limit) throws IOException {
    JsonNode root = objectMapper.readTree(responseBody);
    JsonNode results = root.path("web").path("results");
    List<WebSearchResult> parsed = new ArrayList<>();

    if (!results.isArray()) {
      return parsed;
    }

    for (JsonNode result : results) {
      if (parsed.size() >= limit) {
        break;
      }
      parsed.add(
          new WebSearchResult(
              result.path("title").asString(""),
              result.path("url").asString(""),
              result.path("description").asString(""),
              result.path("age").asString(null)));
    }

    return parsed;
  }

  /** Web上の情報の調査で用いる送信を返す。 */
  private dev.mikoto2000.rei.http.SafeHttpFetcher.Response send(
      HttpRequest request, ProviderProperties provider, int limit) {
    Map<String, String> headers = new LinkedHashMap<>();
    request.headers().map().forEach((name, values) -> headers.put(name, String.join(",", values)));
    var observed =
        WebSearchMetrics.OBSERVED.http(
            provider.getName().trim().toLowerCase(java.util.Locale.ROOT));
    var gate = SearchRequestBudget.captureGate();
    var observer =
        new dev.mikoto2000.rei.http.HttpFetchObserver() {
          /** Web上の情報の調査に関する要求を処理する。 */
          public void request(boolean redirect) {
            gate.run();
            observed.request(redirect);
          }

          /** 対象の現在の状態を、照会用の情報として返す。 */
          public void status(int status) {
            observed.status(status);
          }

          /** Web上の情報の調査に関するバイト数を処理する。 */
          public void bytes(int bytes) {
            observed.bytes(bytes);
          }

          /** 処理を継続できなかった理由を、失敗情報として表す。 */
          public void failure(dev.mikoto2000.rei.http.HttpFetchException.Code code) {
            observed.failure(code);
          }

          /** Web上の情報の調査に関するキャンセルを処理する。 */
          public void cancellation() {
            observed.cancellation();
          }
        };
    return cache.fetch(
        new dev.mikoto2000.rei.http.cache.HttpResponseCache.Request(
            request.uri(),
            headers,
            properties.fetchPolicy(provider),
            dev.mikoto2000.rei.http.cache.HttpResponseCache.Namespace.SEARCH,
            dev.mikoto2000.rei.http.FetchScope.forceRefresh(),
            provider.getName().trim().toLowerCase(java.util.Locale.ROOT) + ":" + limit),
        dev.mikoto2000.rei.http.FetchScope.current(),
        observer,
        fetcher);
  }

  /** 入力を機能内で扱えるよう、DuckDuckGo結果一覧を解釈する。 */
  List<WebSearchResult> parseDuckDuckGoResults(String responseBody, int limit) {
    Document document = Jsoup.parse(responseBody);
    List<WebSearchResult> parsed = new ArrayList<>();

    for (Element result : document.select(".result")) {
      if (parsed.size() >= limit) {
        break;
      }

      Element link = result.selectFirst("a.result__a");
      if (link == null) {
        continue;
      }

      String url = extractDuckDuckGoTargetUrl(link.attr("href"));
      if (url.isBlank()) {
        continue;
      }

      Element snippet = result.selectFirst(".result__snippet");
      parsed.add(
          new WebSearchResult(link.text(), url, snippet == null ? "" : snippet.text(), null));
    }

    return parsed;
  }

  /** 後続の判断に必要な情報を得るため、DuckDuckGo対象URLを抽出する。 */
  String extractDuckDuckGoTargetUrl(String href) {
    if (href == null || href.isBlank()) {
      return "";
    }
    if (!href.startsWith("//duckduckgo.com/l/")
        && !href.startsWith("https://duckduckgo.com/l/")
        && !href.startsWith("http://duckduckgo.com/l/")) {
      return href;
    }

    URI uri = URI.create(href.startsWith("//") ? "https:" + href : href);
    String query = uri.getRawQuery();
    if (query == null || query.isBlank()) {
      return href;
    }

    for (String pair : query.split("&")) {
      int separator = pair.indexOf('=');
      if (separator < 0) {
        continue;
      }
      if ("uddg".equals(pair.substring(0, separator))) {
        return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
      }
    }
    return href;
  }
}
