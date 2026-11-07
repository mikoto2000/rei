package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Web上の情報の調査に必要なWeb検索Orchestratorを扱う。 */
@Service
public class WebSearchOrchestrator {

  /** Web検索サービス。 */
  /** Webページ情報の取得元。 */
  private final WebPageFetcher webPageFetcher;

  /** Web検索検索条件計画の作成。 */
  /** Web検索集計。 */
  private final WebSearchAggregator webSearchAggregator;

  /** この機能の動作設定。 */
  private final Supplier<WebResearchLimits> researchLimits;

  private final WebResearchPipeline.SelectCandidates candidates;

  /** まとめた処理。 */
  private final WebFetchBatch batch;

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchOrchestrator(
      WebSearchService webSearchService,
      WebPageFetcher webPageFetcher,
      WebSearchQueryPlanner webSearchQueryPlanner,
      WebSearchAggregator webSearchAggregator) {
    this(
        webSearchService,
        webPageFetcher,
        webSearchQueryPlanner,
        webSearchAggregator,
        new WebSearchProperties());
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchOrchestrator(
      WebSearchService webSearchService,
      WebPageFetcher webPageFetcher,
      WebSearchQueryPlanner webSearchQueryPlanner,
      WebSearchAggregator webSearchAggregator,
      WebSearchProperties properties) {
    this(
        webSearchService,
        webPageFetcher,
        webSearchQueryPlanner,
        webSearchAggregator,
        properties,
        WebFetchBatch.sharedDefaults());
  }

  /** 指定された値や利用先を使って、WebSearchOrchestratorを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchOrchestrator(
      WebSearchService webSearchService,
      WebPageFetcher webPageFetcher,
      WebSearchQueryPlanner webSearchQueryPlanner,
      WebSearchAggregator webSearchAggregator,
      WebSearchProperties properties,
      WebFetchBatch batch) {
    this.webPageFetcher = webPageFetcher;
    this.webSearchAggregator = webSearchAggregator;
    this.researchLimits = () -> WebResearchLimits.capture(properties);
    this.candidates =
        (query, limit) ->
            WebSearchSelection.search(
                webSearchService, webSearchQueryPlanner, query, limit, properties);
    this.batch = batch;
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public WebSearchContext search(String query, Integer limit)
      throws IOException, InterruptedException {
    long started = System.nanoTime();
    try (var refresh =
        dev.mikoto2000.rei.http.FetchScope.withForceRefresh(
            query != null && WebSearchSelection.needsFreshness(query))) {
      return searchObserved(query, limit);
    } finally {
      WebSearchMetrics.OBSERVED.duration("total", System.nanoTime() - started);
    }
  }

  /** 依頼に関連する情報を得るため、観測を検索する。 */
  private WebSearchContext searchObserved(String query, Integer limit)
      throws IOException, InterruptedException {
    var limits = researchLimits.get();
    int maximum =
        limit == null ? limits.maxResults() : Math.max(1, Math.min(limit, limits.maxResults()));
    var research =
        WebResearchPipeline.run(
            limits,
            candidates,
            batch,
            query,
            maximum,
            limits.maxPageFetches(),
            candidate -> webPageFetcher.fetch(candidate.result()),
            WebResearchPipeline.PAGES);
    WebSearchMetrics.OBSERVED.add("fetch_candidates", research.pageAttempts());
    var aggregated =
        webSearchAggregator.aggregate(
            research.results().stream()
                .filter(page -> !"not_requested".equals(page.fetchStatus()))
                .toList(),
            maximum);
    var context =
        new WebSearchContext(
            aggregated.primaryResults(),
            aggregated.secondaryResults(),
            aggregated.allResults(),
            research.assessment(),
            research.omissions(),
            research.queries(),
            research.pageAttempts());
    context.allResults().forEach(page -> WebSearchMetrics.OBSERVED.text(page.content()));
    return context;
  }
}
