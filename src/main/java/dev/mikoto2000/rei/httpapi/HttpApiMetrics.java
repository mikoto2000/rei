package dev.mikoto2000.rei.httpapi;

import dev.mikoto2000.rei.http.HttpFetchObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.TimeUnit;

/** In-memory Micrometer aggregates with fixed labels, no headers, URLs or payloads. */
public final class HttpApiMetrics {
  /** Use the same global registry as the existing web-search metrics. */
  public static final HttpApiMetrics OBSERVED = new HttpApiMetrics(Metrics.globalRegistry);

  private final MeterRegistry registry;

  /** Allow an isolated registry for deterministic verification. */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "The Micrometer registry must be shared so counters are visible to host exporters.")
  public HttpApiMetrics(MeterRegistry registry) {
    this.registry = registry;
    registry.counter("rei.http.api.retries"); // Transport retries are deliberately disabled.
  }

  void call(boolean write) {
    registry.counter("rei.http.api.calls", "kind", write ? "write" : "read").increment();
  }

  void duration(String stage, long nanos) {
    registry.timer("rei.http.api." + stage + ".duration").record(nanos, TimeUnit.NANOSECONDS);
  }

  void result(HttpApiResult result) {
    if (result.error() != null)
      registry.counter("rei.http.api.errors", "code", result.error()).increment();
    registry.summary("rei.http.api.response.bytes").record(result.responseBytes());
    registry.summary("rei.http.api.output.bytes").record(result.outputBytes());
    if (result.truncated()) registry.counter("rei.http.api.truncations").increment();
    if (result.json() != null || result.text() != null) {
      String text = result.json() == null ? result.text() : result.json().toString();
      registry
          .summary("rei.http.api.estimated.tokens")
          .record(dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(text));
    }
  }

  HttpFetchObserver observer(Runnable sent) {
    return new HttpFetchObserver() {
      @Override
      public void request(boolean redirect) {
        sent.run();
        registry.counter("rei.http.api.requests").increment();
        if (redirect) registry.counter("rei.http.api.redirects").increment();
      }

      @Override
      public void status(int status) {
        registry.counter("rei.http.api.http", "status", Integer.toString(status)).increment();
      }

      @Override
      public void bytes(int count) {
        registry.counter("rei.http.api.wire.bytes").increment(count);
      }
    };
  }
}
