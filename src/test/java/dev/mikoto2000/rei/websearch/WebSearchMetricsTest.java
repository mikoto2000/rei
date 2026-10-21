package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Web検索計測値の結果と状態の契約を検証するテスト。 */
class WebSearchMetricsTest {
  /** テキストについて、共有予算概算を利用することを確認する。 */
  @Test
  void textUsesTheSharedBudgetEstimatorForMixedJapaneseAndEnglish() {
    var registry = new SimpleMeterRegistry();
    var metrics = new WebSearchMetrics(registry);
    String text = "日本語 API 🐈";
    metrics.text(text);
    assertEquals(
        dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative().text(text),
        registry.get("rei.web.search.events").tag("event", "estimated_tokens").counter().count());
    assertEquals(
        text.length(),
        registry.get("rei.web.search.events").tag("event", "output_characters").counter().count());
  }

  /** Web上の情報の調査で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void recordsOnlyObservedEventsAndBoundsProviderLabels() {
    var registry = new SimpleMeterRegistry();
    var metrics = new WebSearchMetrics(registry);
    metrics.add("results", 3);
    metrics.provider("brave");
    metrics.provider("https://secret.example/query");
    metrics.duration("search", 1_000_000);
    assertEquals(
        3, registry.get("rei.web.search.events").tag("event", "results").counter().count());
    assertEquals(
        1, registry.get("rei.web.search.providers").tag("provider", "other").counter().count());
    assertNull(registry.find("rei.web.search.events").tag("event", "cache_hit").counter());
    assertEquals(1, registry.get("rei.web.search.duration").tag("stage", "search").timer().count());
  }
}
