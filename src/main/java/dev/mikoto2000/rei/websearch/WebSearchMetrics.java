package dev.mikoto2000.rei.websearch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Low-cardinality observed events only; no query, URL, credential or body labels. */
public final class WebSearchMetrics {
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
    add("estimated_tokens", (length + 1L) / 2);
  }
}
