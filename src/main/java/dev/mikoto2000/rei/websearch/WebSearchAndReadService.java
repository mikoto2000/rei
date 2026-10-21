package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.urlfetch.UrlContentFetchResult;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/** Web 検索と検索結果本文の取得を一つの操作として実行する。 */
@Service
public class WebSearchAndReadService {
  /** 既定本文を取得する上位件数。 */
  static final int DEFAULT_READ_TOP = 3;

  /** Web検索サービス。 */
  private final WebSearchService webSearchService;

  /** URL内容取得サービス。 */
  private final UrlContentFetchService urlContentFetchService;

  /** Webページ情報の抽出。 */
  private final WebPageExtractor webPageExtractor;

  /** この機能の動作設定。 */
  private final WebSearchProperties properties;

  /** 計画の作成。 */
  private final WebSearchQueryPlanner planner;

  /** まとめた処理。 */
  private final WebFetchBatch batch;

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchAndReadService(
      WebSearchService webSearchService,
      UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor,
      WebSearchProperties properties) {
    this(
        webSearchService,
        urlContentFetchService,
        webPageExtractor,
        properties,
        new WebSearchQueryPlanner());
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchAndReadService(
      WebSearchService webSearchService,
      UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor,
      WebSearchProperties properties,
      WebSearchQueryPlanner planner) {
    this(
        webSearchService,
        urlContentFetchService,
        webPageExtractor,
        properties,
        planner,
        WebFetchBatch.sharedDefaults());
  }

  /** 指定された値や利用先を使って、WebSearchAndReadServiceを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchAndReadService(
      WebSearchService webSearchService,
      UrlContentFetchService urlContentFetchService,
      WebPageExtractor webPageExtractor,
      WebSearchProperties properties,
      WebSearchQueryPlanner planner,
      WebFetchBatch batch) {
    this.webSearchService = webSearchService;
    this.urlContentFetchService = urlContentFetchService;
    this.webPageExtractor = webPageExtractor;
    this.properties = properties;
    this.planner = planner;
    this.batch = batch;
  }

  /** 検索と本文取得に共通の予算を適用し、出典付きの調査結果を返す。 */
  public WebSearchAndReadResponse searchAndRead(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    try (var refresh =
        dev.mikoto2000.rei.http.FetchScope.withForceRefresh(
            request != null
                && (Boolean.TRUE.equals(request.forceRefresh())
                    || request.query() != null
                        && WebSearchSelection.needsFreshness(request.query())))) {
      return searchAndReadObserved(request);
    } finally {
      WebSearchMetrics.OBSERVED.duration("total", System.nanoTime() - started);
    }
  }

  /** 検索候補から必要な本文を取得し、根拠の充足度と省略理由をまとめる。 */
  private WebSearchAndReadResponse searchAndReadObserved(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    ValidatedRequest validated = validate(request);
    // 本文が不要な依頼では、検索条件の展開をせず候補URLの取得にとどめる。
    var metadataPlanner =
        validated.readTop() == 0
            ? new WebSearchQueryPlanner() {
              /** 依頼を進めるための候補や手順を組み立てる。 */
              public List<String> plan(String query) {
                return List.of(query);
              }
            }
            : planner;
    var research =
        WebResearchPipeline.run(
            webSearchService,
            metadataPlanner,
            properties,
            batch,
            validated.query(),
            validated.maxResults(),
            validated.readTop(),
            candidate -> fetch(candidate.result()),
            new WebResearchPipeline.Adapter<WebSearchAndReadItem>() {
              /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
              public WebSearchAndReadItem failed(WebSearchResult result, String error) {
                return WebSearchAndReadItem.fromPage(
                    WebResearchPipeline.PAGES.failed(result, error),
                    null,
                    "Page acquisition did not complete");
              }

              /** 対象の処理を要求していないことを、結果の状態として表す。 */
              public WebSearchAndReadItem notRequested(WebSearchResult result) {
                return WebSearchAndReadService.this.notRequested(result);
              }

              /** Web上の情報の調査で用いる別名一覧を返す。 */
              public WebSearchAndReadItem aliases(
                  WebSearchAndReadItem value, List<WebSourceAlias> aliases) {
                return value.withAliases(aliases);
              }

              /** Web上の情報の調査で用いる根拠を返す。 */
              public WebSearchPage evidence(WebSearchAndReadItem value) {
                return value.asPage();
              }

              /** 同じ内容を重ねて扱わないよう、重複する項目を整理する。 */
              public List<WebSearchAndReadItem> deduplicate(List<WebSearchAndReadItem> values) {
                return WebContentDeduplication.items(values);
              }
            });
    WebSearchMetrics.OBSERVED.add("fetch_candidates", research.pageAttempts());
    research.results().forEach(item -> WebSearchMetrics.OBSERVED.text(item.content()));
    return new WebSearchAndReadResponse(
        validated.query(),
        research.results(),
        research.assessment(),
        research.omissions(),
        research.queries(),
        research.pageAttempts());
  }

  /** 候補ページの本文を抽出し、取得や抽出の失敗も候補ごとの結果として返す。 */
  private WebSearchAndReadItem fetch(WebSearchResult result) {
    UrlContentFetchResult fetched = safeFetch(result.url());
    // 一つのページを取得できなくても、検索候補と失敗理由を結果に残す。
    if (!fetched.success()) {
      return new WebSearchAndReadItem(
          result.title(),
          result.url(),
          result.snippet(),
          result.publishedAt(),
          null,
          null,
          "failed",
          fetched.errorType(),
          fetched.errorMessage(),
          false);
    }
    try {
      WebSearchPage page = webPageExtractor.extract(result, fetched.content());
      var item =
          WebSearchAndReadItem.fromPage(
              page.withEvidence(
                  fetched.retrievedAt(), fetched.validatedAt(), page.excerpts(), page.omissions()),
              fetched.contentType(),
              null);
      // 転送先も出典として追跡できるよう、検索時のURLと取得後のURLを関連付ける。
      if (fetched.finalUrl() != null && !fetched.finalUrl().equals(result.url()))
        item =
            item.withAliases(
                List.of(
                    new WebSourceAlias(
                        fetched.finalUrl(), page.title(), page.publishedAt(), "http_redirect")));
      return item;
    } catch (RuntimeException exception) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(exception);
      return new WebSearchAndReadItem(
          result.title(),
          result.url(),
          result.snippet(),
          result.publishedAt(),
          null,
          fetched.contentType(),
          "failed",
          "EXTRACTION_ERROR",
          "Failed to extract page content",
          false);
    }
  }

  /** 取得制約と中断要求を守ってURLを読み、通常の取得失敗を結果として返す。 */
  private UrlContentFetchResult safeFetch(String url) {
    try {
      return urlContentFetchService.fetch(url, properties.fetchPolicy());
    } catch (RuntimeException exception) {
      dev.mikoto2000.rei.http.FetchOperation.propagateControls(exception);
      return UrlContentFetchResult.failure("FETCH_ERROR", "Failed to fetch URL");
    }
  }

  /** 検索候補を残したまま、本文取得を行っていないことを明示する。 */
  private WebSearchAndReadItem notRequested(WebSearchResult result) {
    return new WebSearchAndReadItem(
        result.title(),
        result.url(),
        result.snippet(),
        result.publishedAt(),
        null,
        null,
        "not_requested",
        null,
        null,
        false);
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private ValidatedRequest validate(WebSearchAndReadRequest request) {
    if (request == null) throw new IllegalArgumentException("request must not be null");
    if (request.query() == null || request.query().isBlank()) {
      throw new IllegalArgumentException("query must not be blank");
    }
    int maxResults =
        request.maxResults() == null ? properties.getMaxResults() : request.maxResults();
    if (maxResults <= 0 || maxResults > properties.getMaxResults()) {
      throw new IllegalArgumentException(
          "maxResults must be between 1 and " + properties.getMaxResults());
    }
    int readTop =
        request.readTop() == null ? Math.min(DEFAULT_READ_TOP, maxResults) : request.readTop();
    if (readTop < 0 || readTop > maxResults) {
      throw new IllegalArgumentException("readTop must be between 0 and maxResults");
    }
    return new ValidatedRequest(request.query().trim(), maxResults, readTop);
  }

  /**
   * Web上の情報の調査で受け渡す検証済み要求を表す。
   *
   * @param query 検索条件
   * @param maxResults 検索結果の件数上限
   * @param readTop 本文を取得する上位件数
   */
  private record ValidatedRequest(String query, int maxResults, int readTop) {}
}
