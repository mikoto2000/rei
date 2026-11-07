package dev.mikoto2000.rei.websearch;

import java.time.Duration;

/** Limits owned by one research operation. */
record WebResearchLimits(
    int maxResults,
    int maxQueries,
    int maxApiCalls,
    int maxPageFetches,
    int fetchParallelism,
    int initialPageFetches,
    Duration fetchBatchTimeout,
    Duration timeout,
    long totalWireBytes,
    long totalDecodedBytes) {
  static WebResearchLimits capture(WebSearchProperties properties) {
    properties.validateSelection();
    return new WebResearchLimits(
        properties.getMaxResults(),
        properties.getMaxSearchQueries(),
        properties.getMaxSearchApiCalls(),
        properties.getMaxPageFetches(),
        properties.getFetchParallelism(),
        properties.getInitialPageFetches(),
        Duration.ofSeconds(properties.getFetchBatchTimeoutSeconds()),
        Duration.ofSeconds(properties.getResearchTimeoutSeconds()),
        properties.getTotalWireBytes(),
        properties.getTotalDecodedBytes());
  }
}
