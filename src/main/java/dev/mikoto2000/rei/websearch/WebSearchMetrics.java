package dev.mikoto2000.rei.websearch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Low-cardinality observed events only; no query, URL, credential or body labels. */
public final class WebSearchMetrics {
  private static final dev.mikoto2000.rei.core.contextbudget.TokenEstimator TOKENS =
      dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative();
  public static final WebSearchMetrics OBSERVED = new WebSearchMetrics(Metrics.globalRegistry);
  private final MeterRegistry registry;
  public WebSearchMetrics(MeterRegistry registry) { this.registry = registry; }
  public void add(String event, long amount) {
    if (amount > 0) registry.counter("rei.web.search.events", "event", event).increment(amount);
  }
  public void provider(String provider) {
    registry.counter("rei.web.search.providers", "provider",
        Set.of("duckduckgo", "brave").contains(provider) ? provider : "other").increment();
  }
  public void status(int status) {
    registry.counter("rei.web.search.http", "status", Integer.toString(status)).increment();
  }
  public void duration(String stage, long nanos) {
    Timer.builder("rei.web.search.duration").tag("stage", stage)
        .publishPercentiles(0.5, 0.95).register(registry).record(nanos, TimeUnit.NANOSECONDS);
  }
  public void text(String text) {
    int length = text == null ? 0 : text.length();
    add("output_characters", length);
    add("estimated_tokens", TOKENS.text(text));
  }
  public dev.mikoto2000.rei.http.HttpFetchObserver http(String provider) {
    return new dev.mikoto2000.rei.http.HttpFetchObserver() {
      public void request(boolean redirect) {
        add("http_requests", 1);
        if (redirect) add("redirects", 1);
        if (provider != null) { provider(provider); add("search_api_calls", 1); }
      }
      public void status(int status) { WebSearchMetrics.this.status(status); }
      public void bytes(int bytes) { add("received_bytes", bytes); }
      public void cancellation() { add("cancellations", 1); }
      public void failure(dev.mikoto2000.rei.http.HttpFetchException.Code code) {
        if (code == dev.mikoto2000.rei.http.HttpFetchException.Code.CONNECT_TIMEOUT
            || code == dev.mikoto2000.rei.http.HttpFetchException.Code.READ_TIMEOUT
            || code == dev.mikoto2000.rei.http.HttpFetchException.Code.TOTAL_TIMEOUT) add("timeouts", 1);
      }
    };
  }
}
