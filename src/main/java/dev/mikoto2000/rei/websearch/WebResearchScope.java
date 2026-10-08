package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.*;
import java.time.Duration;
import java.util.*;

/** Finite quotas shared by the initial search and deterministic supplements; no recursive planning. */
final class WebResearchScope implements AutoCloseable {
  private static final ThreadLocal<WebResearchScope> CURRENT = new ThreadLocal<>();
  private final boolean owner;
  private final WebResearchScope root;
  private final WebSearchProperties properties;
  private final Set<String> queries = new LinkedHashSet<>();
  private final FetchScope fetch;
  private final TransferScope transfer;
  private final SearchRequestBudget api;
  private WebResearchScope(WebSearchProperties properties) {
    properties.validateSelection(); this.properties = properties; owner = CURRENT.get() == null;
    root=owner?this:CURRENT.get();
    if (owner) {
      CURRENT.set(this); fetch = FetchScope.enter(FetchScope.current().withTimeout(Duration.ofSeconds(properties.getResearchTimeoutSeconds())));
      transfer = TransferScope.enter(TransferScope.current() == null ? new TransferBudget(properties.getTotalWireBytes(), properties.getTotalDecodedBytes()) : TransferScope.current());
      api = SearchRequestBudget.enter(properties.getMaxSearchApiCalls());
    } else { fetch = null; transfer = null; api = null; }
  }
  static WebResearchScope enter(WebSearchProperties properties) { return new WebResearchScope(properties); }
  static boolean registerQuery(String query) {
    var scope = CURRENT.get(); if (scope == null) return true;
    if (scope.queries.contains(query) || scope.queries.size() >= scope.properties.getMaxSearchQueries()) return false;
    scope.queries.add(query); return true;
  }
  static int queryCount() { var scope = CURRENT.get(); return scope == null ? 0 : scope.queries.size(); }
  int totalQueries() { return root.queries.size(); }
  public void close() { if (owner) { api.close(); transfer.close(); fetch.close(); CURRENT.remove(); } }
}
