package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.FetchOperation;
import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.http.HttpFetchException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Web上の情報の調査に必要なWeb調査処理の連携を扱う。
 *
 * <p>One bounded, iterative research path for normal and indexed-knowledge web supplementation.
 */
final class WebResearchPipeline {
  /** Web上の情報の調査に必要な連携の窓口を扱う。 */
  interface Adapter<T> {
    /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
    T failed(WebSearchResult result, String error);

    /** 対象の処理を要求していないことを、結果の状態として表す。 */
    T notRequested(WebSearchResult result);

    /** Web上の情報の調査で用いる別名一覧を返す。 */
    T aliases(T value, List<WebSourceAlias> aliases);

    /** Web上の情報の調査で用いる根拠を返す。 */
    WebSearchPage evidence(T value);

    /** 同じ内容を重ねて扱わないよう、重複する項目を整理する。 */
    List<T> deduplicate(List<T> values);
  }

  /** ページ一覧。 */
  static final Adapter<WebSearchPage> PAGES =
      new Adapter<>() {
        /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
        public WebSearchPage failed(WebSearchResult result, String error) {
          return row(result, "failed", error);
        }

        /** 対象の処理を要求していないことを、結果の状態として表す。 */
        public WebSearchPage notRequested(WebSearchResult result) {
          return row(result, "not_requested", null);
        }

        /** Web上の情報の調査で用いる行を返す。 */
        private WebSearchPage row(WebSearchResult result, String status, String error) {
          return new WebSearchPage(
              result.title(),
              result.url(),
              result.snippet(),
              result.publishedAt(),
              result.snippet(),
              false,
              null,
              List.of(WebSourceAlias.from(result)),
              status,
              error);
        }

        /** Web上の情報の調査で用いる別名一覧を返す。 */
        public WebSearchPage aliases(WebSearchPage value, List<WebSourceAlias> aliases) {
          return value.withAliases(aliases);
        }

        /** Web上の情報の調査で用いる根拠を返す。 */
        public WebSearchPage evidence(WebSearchPage value) {
          return value;
        }

        /** 同じ内容を重ねて扱わないよう、重複する項目を整理する。 */
        public List<WebSearchPage> deduplicate(List<WebSearchPage> values) {
          return WebContentDeduplication.pages(values);
        }
      };

  /**
   * Web上の情報の調査で受け渡す調査を表す。
   *
   * @param results 結果一覧
   * @param assessment 評価
   * @param omissions 省略した内容
   * @param queries 検索条件一覧
   * @param pageAttempts ページ試行回数
   */
  record Research<T>(
      List<T> results,
      WebRetrievalAssessment assessment,
      List<String> omissions,
      int queries,
      int pageAttempts) {}

  /** 検索・本文取得を予算内で反復し、確認できた根拠と不足・省略の理由をまとめる。 */
  static <T> Research<T> run(
      WebSearchService search,
      WebSearchQueryPlanner planner,
      WebSearchProperties properties,
      WebFetchBatch batch,
      String query,
      int limit,
      int readTop,
      WebFetchBatch.Fetcher<T> loader,
      Adapter<T> adapter)
      throws IOException, InterruptedException {
    return run(
        WebResearchLimits.capture(properties),
        (plannedQuery, requested) ->
            WebSearchSelection.search(search, planner, plannedQuery, requested, properties),
        batch,
        query,
        limit,
        readTop,
        loader,
        adapter);
  }

  @FunctionalInterface
  interface SelectCandidates {
    List<WebSearchSelection.Candidate> search(String query, int limit)
        throws IOException, InterruptedException;
  }

  static <T> Research<T> run(
      WebResearchLimits limits,
      SelectCandidates selection,
      WebFetchBatch batch,
      String query,
      int limit,
      int readTop,
      WebFetchBatch.Fetcher<T> loader,
      Adapter<T> adapter)
      throws IOException, InterruptedException {
    var candidates = new LinkedHashMap<String, WebSearchSelection.Candidate>();
    var loaded = new LinkedHashMap<String, T>();
    var attempted = new HashSet<String>();
    var omissions = new ArrayList<String>();
    int attempts = 0;
    int maximumReads = Math.min(readTop, limits.maxPageFetches());
    WebRetrievalAssessment assessment =
        WebRetrievalAssessment.unknown(
            maximumReads == 0 ? "body_not_requested" : "no_readable_verified_body");
    List<WebSearchPage> evidence = List.of();
    var scope = WebResearchScope.enter(limits);
    try (scope) {
      var extraction = WebExtractionScope.enter(query);
      try (extraction) {
        add(query, candidates, selection.search(query, limit));
        boolean first = true;
        boolean triedUnreadBeforeSearch = false;
        // 根拠が十分になるか本文取得の予算に達するまで、追加調査を行う。
        while (attempts < maximumReads && !"sufficient".equals(assessment.status())) {
          FetchScope.current().check();
          var previousKeys = Set.copyOf(candidates.keySet());
          var pending = unread(query, candidates, attempted);
          int remainingReads = maximumReads - attempts;
          boolean canSearchAgain =
              WebResearchScope.queryCount() < limits.maxQueries() && !SearchRequestBudget.exhausted();
          int parallelReads = Math.min(remainingReads, limits.fetchParallelism());
          // 可能なら、並列取得でも不足した場合に追加検索の候補を読む枠を1件残す。
          if (canSearchAgain && remainingReads > 2)
            parallelReads = Math.min(parallelReads, remainingReads - 1);
          var independent = assessment.independentCandidates(query, evidence, pending, parallelReads);
          var promising =
              first || triedUnreadBeforeSearch || !independent.isEmpty()
                  ? null
                  : assessment.promisingCandidate(query, evidence, pending);
          boolean supplement =
              !first
                  && canSearchAgain
                  && (triedUnreadBeforeSearch || (promising == null && independent.isEmpty()));
          if (supplement) {
            try {
              add(query, candidates, selection.search(query, limit));
            } catch (IOException failed) {
              FetchOperation.propagateControls(failed);
              WebSearchSelection.recordSearchFailure(failed);
            } catch (RuntimeException failed) {
              FetchOperation.propagateControls(failed);
              if (failed instanceof HttpFetchException http
                  && Set.of(
                          HttpFetchException.Code.TOTAL_TIMEOUT,
                          HttpFetchException.Code.TRANSFER_BUDGET,
                          HttpFetchException.Code.REQUEST_BUDGET)
                      .contains(http.code())) throw http;
              WebSearchSelection.recordSearchFailure(failed);
            }
            pending =
                unread(query, candidates, attempted).stream()
                    .sorted(
                        Comparator.comparing(
                            candidate ->
                                previousKeys.contains(
                                    WebSearchSelection.normalizeUrl(candidate.result().url()))))
                    .toList();
            independent = assessment.independentCandidates(query, evidence, pending, parallelReads);
          }
          if (pending.isEmpty()) break;
          int window = Math.min(remainingReads, first ? limits.initialPageFetches() : 1);
          var selected =
              !independent.isEmpty()
                  ? independent
                  : promising == null ? pending.stream().limit(window).toList() : List.of(promising);
          triedUnreadBeforeSearch = !supplement && (promising != null || !independent.isEmpty());
          first = false;
          for (var candidate : selected)
            attempted.add(WebSearchSelection.normalizeUrl(candidate.result().url()));
          // 取得が失敗した候補も試行回数に含め、失敗の繰り返しで予算を超えないようにする。
          attempts += selected.size();
          var outcomes = batch.fetch(selected, limits.fetchBatchTimeout(), loader);
          for (int i = 0; i < outcomes.size(); i++) {
            var candidate = selected.get(i);
            var outcome = outcomes.get(i);
            T value =
                outcome.success()
                    ? outcome.value()
                    : adapter.failed(candidate.result(), outcome.errorType());
            loaded.put(
                WebSearchSelection.normalizeUrl(candidate.result().url()),
                adapter.aliases(value, candidate.aliases()));
          }
          if (outcomes.stream().anyMatch(outcome -> "TRANSFER_BUDGET".equals(outcome.errorType())))
            throw new HttpFetchException(HttpFetchException.Code.TRANSFER_BUDGET);
          evidence =
              adapter.deduplicate(new ArrayList<>(loaded.values())).stream()
                  .map(adapter::evidence)
                  .toList();
          assessment = WebRetrievalAssessment.assess(query, evidence);
        }
        if (scope.searchFailed()) omissions.add("additional_search_failed");
        var rows = new ArrayList<T>();
        for (var candidate :
            WebSearchSelection.choose(
                query, new ArrayList<>(candidates.values()), candidates.size())) {
          T value = loaded.get(WebSearchSelection.normalizeUrl(candidate.result().url()));
          rows.add(
              adapter.aliases(
                  value == null ? adapter.notRequested(candidate.result()) : value,
                  candidate.aliases()));
        }
        var deduplicated = adapter.deduplicate(rows);
        var chosen = new HashSet<T>();
        // 本文を取得できた根拠を優先して返し、残りの枠に未取得や失敗の候補を残す。
        for (T value : deduplicated)
          if ("success".equals(adapter.evidence(value).fetchStatus()) && chosen.size() < limit)
            chosen.add(value);
        for (T value : deduplicated) if (chosen.size() < limit) chosen.add(value);
        var result = deduplicated.stream().filter(chosen::contains).toList();
        if (result.size() < deduplicated.size()) omissions.add("result_limit");
        assessment =
            omissions.contains("additional_search_failed")
                ? WebRetrievalAssessment.unknown("additional_search_failed")
                : maximumReads == 0
                    ? WebRetrievalAssessment.unknown("body_not_requested")
                    : WebRetrievalAssessment.assess(
                        query, result.stream().map(adapter::evidence).toList());
        return new Research<>(
            result, assessment, List.copyOf(omissions), WebResearchScope.queryCount(), attempts);
      }
    } catch (HttpFetchException limitReached) {
      if (!Set.of(
              HttpFetchException.Code.TOTAL_TIMEOUT,
              HttpFetchException.Code.TRANSFER_BUDGET,
              HttpFetchException.Code.REQUEST_BUDGET)
          .contains(limitReached.code())) throw limitReached;
      // 取得制限に達した場合も確認済みの結果を返し、根拠の充足度は未確定として伝える。
      if (scope.searchFailed()) omissions.add("additional_search_failed");
      omissions.add(limitReached.code().name());
      return new Research<>(
          adapter.deduplicate(new ArrayList<>(loaded.values())).stream().limit(limit).toList(),
          WebRetrievalAssessment.unknown(limitReached.code().name()),
          List.copyOf(omissions),
          scope.totalQueries(),
          attempts);
    }
  }

  /** 未読候補を既存の順位と出典の多様性に従って並べる。 */
  private static List<WebSearchSelection.Candidate> unread(
      String query, Map<String, WebSearchSelection.Candidate> candidates, Set<String> attempted) {
    return WebSearchSelection.choose(query, new ArrayList<>(candidates.values()), candidates.size())
        .stream()
        .filter(
            candidate ->
                !attempted.contains(WebSearchSelection.normalizeUrl(candidate.result().url())))
        .toList();
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  private static void add(
      String query,
      Map<String, WebSearchSelection.Candidate> candidates,
      List<WebSearchSelection.Candidate> additions) {
    for (var candidate : additions) {
      String key = WebSearchSelection.normalizeUrl(candidate.result().url());
      var old = candidates.get(key);
      if (old == null)
        candidates.put(
            key,
            new WebSearchSelection.Candidate(
                candidate.result(), candidate.aliases(), candidates.size()));
      else {
        var aliases = new ArrayList<>(old.aliases());
        for (var alias : candidate.aliases()) if (!aliases.contains(alias)) aliases.add(alias);
        candidates.put(
            key,
            new WebSearchSelection.Candidate(
                WebSearchSelection.priority(query, candidate.result())
                        > WebSearchSelection.priority(query, old.result())
                    ? candidate.result()
                    : old.result(),
                aliases,
                old.rank()));
      }
    }
  }
}
