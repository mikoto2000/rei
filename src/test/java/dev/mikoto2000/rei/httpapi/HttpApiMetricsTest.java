package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.mikoto2000.rei.http.SafeHttpFetcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Metrics retain only bounded outcome labels and sizes. */
class HttpApiMetricsTest {
  @Test
  void recordsFailedCallsWithoutSensitiveLabels() {
    var registry = new SimpleMeterRegistry();
    var metrics = new HttpApiMetrics(registry);
    var service =
        new HttpApiService(
            new SafeHttpFetcher(),
            new HttpApiProperties(null, null, null, null, null),
            name -> null,
            metrics);
    var result =
        service.read(
            new HttpApiRequest(
                "http://127.0.0.1:1/private?secret=fixture",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    assertEquals("URL_NOT_ALLOWED", result.error());
    assertEquals(1, registry.get("rei.http.api.calls").tag("kind", "read").counter().count());
    assertEquals(1, registry.get("rei.http.api.tool.duration").timer().count());
    assertEquals(
        1, registry.get("rei.http.api.errors").tag("code", "URL_NOT_ALLOWED").counter().count());
    registry.forEachMeter(meter -> assertFalse(meter.getId().toString().contains("fixture")));
  }
}
