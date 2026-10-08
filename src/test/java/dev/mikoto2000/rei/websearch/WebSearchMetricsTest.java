package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class WebSearchMetricsTest {
  @Test void recordsOnlyObservedEventsAndBoundsProviderLabels() {
    var registry = new SimpleMeterRegistry();
    var metrics = new WebSearchMetrics(registry);
    metrics.add("results", 3);
    metrics.provider("brave");
    metrics.provider("https://secret.example/query");
    metrics.duration("search", 1_000_000);
    assertEquals(3, registry.get("rei.web.search.events").tag("event", "results").counter().count());
    assertEquals(1, registry.get("rei.web.search.providers").tag("provider", "other").counter().count());
    assertNull(registry.find("rei.web.search.events").tag("event", "cache_hit").counter());
    assertEquals(1, registry.get("rei.web.search.duration").tag("stage", "search").timer().count());
  }
}
