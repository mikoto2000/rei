package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

/** One bounded, iterative research path for normal and indexed-knowledge web supplementation. */
final class WebResearchPipeline {
  interface Adapter<T> {
    T failed(WebSearchResult result, String error);
    T notRequested(WebSearchResult result);
    T aliases(T value, List<WebSourceAlias> aliases);
    WebSearchPage evidence(T value);
    List<T> deduplicate(List<T> values);
  }
  static final Adapter<WebSearchPage> PAGES = new Adapter<>() {
    public WebSearchPage failed(WebSearchResult result, String error) { return row(result, "failed", error); }
    public WebSearchPage notRequested(WebSearchResult result) { return row(result, "not_requested", null); }
    private WebSearchPage row(WebSearchResult result, String status, String error) {
      return new WebSearchPage(result.title(), result.url(), result.snippet(), result.publishedAt(), result.snippet(), false,
          null, List.of(WebSourceAlias.from(result)), status, error);
    }
    public WebSearchPage aliases(WebSearchPage value, List<WebSourceAlias> aliases) { return value.withAliases(aliases); }
    public WebSearchPage evidence(WebSearchPage value) { return value; }
    public List<WebSearchPage> deduplicate(List<WebSearchPage> values) { return WebContentDeduplication.pages(values); }
  };
  record Research<T>(List<T> results, WebRetrievalAssessment assessment, List<String> omissions, int queries, int pageAttempts) { }
  static <T> Research<T> run(WebSearchService search, WebSearchQueryPlanner planner, WebSearchProperties properties,
      WebFetchBatch batch, String query, int limit, int readTop, WebFetchBatch.Fetcher<T> loader, Adapter<T> adapter)
      throws IOException, InterruptedException {
    var candidates = new LinkedHashMap<String, WebSearchSelection.Candidate>(); var loaded = new LinkedHashMap<String, T>();
    var attempted = new HashSet<String>(); var omissions = new ArrayList<String>(); int attempts = 0;
    int maximumReads = Math.min(readTop, properties.getMaxPageFetches());
    WebRetrievalAssessment assessment = WebRetrievalAssessment.unknown(maximumReads == 0 ? "body_not_requested" : "no_readable_verified_body");
    var scope=WebResearchScope.enter(properties);
    try (scope; var extraction = WebExtractionScope.enter(query)) {
      add(query,candidates, WebSearchSelection.search(search, planner, query, limit, properties));
      boolean first = true;
      while (attempts < maximumReads && !"sufficient".equals(assessment.status())) {
        FetchScope.current().check();
        var previousKeys = Set.copyOf(candidates.keySet());
        if (!first && WebResearchScope.queryCount() < properties.getMaxSearchQueries() && !SearchRequestBudget.exhausted()) {
          try { add(query,candidates, WebSearchSelection.search(search, planner, query, limit, properties)); }
          catch(IOException failed) { omissions.add("additional_search_failed"); break; }
          catch(RuntimeException failed) {
            FetchOperation.propagateControls(failed);
            if(failed instanceof HttpFetchException http && Set.of(HttpFetchException.Code.TOTAL_TIMEOUT,
                HttpFetchException.Code.TRANSFER_BUDGET,HttpFetchException.Code.REQUEST_BUDGET).contains(http.code()))throw http;
            omissions.add("additional_search_failed"); break;
          }
        }
        var pending = WebSearchSelection.choose(query, new ArrayList<>(candidates.values()), candidates.size()).stream()
            .filter(candidate -> !attempted.contains(WebSearchSelection.normalizeUrl(candidate.result().url())))
            .sorted(Comparator.comparing(candidate -> previousKeys.contains(WebSearchSelection.normalizeUrl(candidate.result().url())))).toList();
        if (pending.isEmpty()) break;
        int window = Math.min(maximumReads - attempts, first ? properties.getInitialPageFetches() : 1);
        var selected = pending.stream().limit(window).toList(); first = false;
        for (var candidate : selected) attempted.add(WebSearchSelection.normalizeUrl(candidate.result().url()));
        attempts += selected.size();
        var outcomes = batch.fetch(selected, Duration.ofSeconds(properties.getFetchBatchTimeoutSeconds()), loader);
        for (int i = 0; i < outcomes.size(); i++) {
          var candidate = selected.get(i); var outcome = outcomes.get(i);
          T value = outcome.success() ? outcome.value() : adapter.failed(candidate.result(), outcome.errorType());
          loaded.put(WebSearchSelection.normalizeUrl(candidate.result().url()), adapter.aliases(value, candidate.aliases()));
        }
        if(outcomes.stream().anyMatch(outcome -> "TRANSFER_BUDGET".equals(outcome.errorType())))
          throw new HttpFetchException(HttpFetchException.Code.TRANSFER_BUDGET);
        assessment = WebRetrievalAssessment.assess(query, adapter.deduplicate(new ArrayList<>(loaded.values())).stream().map(adapter::evidence).toList());
      }
      var rows = new ArrayList<T>();
      for (var candidate : WebSearchSelection.choose(query, new ArrayList<>(candidates.values()), candidates.size())) {
        T value = loaded.get(WebSearchSelection.normalizeUrl(candidate.result().url()));
        rows.add(adapter.aliases(value == null ? adapter.notRequested(candidate.result()) : value, candidate.aliases()));
      }
      var deduplicated = adapter.deduplicate(rows); var chosen = new HashSet<T>();
      for (T value : deduplicated) if ("success".equals(adapter.evidence(value).fetchStatus()) && chosen.size() < limit) chosen.add(value);
      for (T value : deduplicated) if (chosen.size() < limit) chosen.add(value);
      var result = deduplicated.stream().filter(chosen::contains).toList();
      if (result.size() < deduplicated.size()) omissions.add("result_limit");
      assessment = omissions.contains("additional_search_failed") ? WebRetrievalAssessment.unknown("additional_search_failed")
          : maximumReads == 0 ? WebRetrievalAssessment.unknown("body_not_requested")
          : WebRetrievalAssessment.assess(query, result.stream().map(adapter::evidence).toList());
      return new Research<>(result, assessment, List.copyOf(omissions), WebResearchScope.queryCount(), attempts);
    } catch (HttpFetchException limitReached) {
      if (!Set.of(HttpFetchException.Code.TOTAL_TIMEOUT, HttpFetchException.Code.TRANSFER_BUDGET, HttpFetchException.Code.REQUEST_BUDGET).contains(limitReached.code())) throw limitReached;
      omissions.add(limitReached.code().name());
      return new Research<>(adapter.deduplicate(new ArrayList<>(loaded.values())).stream().limit(limit).toList(), WebRetrievalAssessment.unknown(limitReached.code().name()),
          List.copyOf(omissions), scope.totalQueries(), attempts);
    }
  }
  private static void add(String query, Map<String, WebSearchSelection.Candidate> candidates, List<WebSearchSelection.Candidate> additions) {
    for (var candidate : additions) {
      String key = WebSearchSelection.normalizeUrl(candidate.result().url());
      var old = candidates.get(key);
      if (old == null) candidates.put(key, new WebSearchSelection.Candidate(candidate.result(), candidate.aliases(), candidates.size()));
      else {
        var aliases = new ArrayList<>(old.aliases()); for (var alias : candidate.aliases()) if (!aliases.contains(alias)) aliases.add(alias);
        candidates.put(key, new WebSearchSelection.Candidate(WebSearchSelection.priority(query,candidate.result())>
            WebSearchSelection.priority(query,old.result())?candidate.result():old.result(), aliases, old.rank()));
      }
    }
  }
}
