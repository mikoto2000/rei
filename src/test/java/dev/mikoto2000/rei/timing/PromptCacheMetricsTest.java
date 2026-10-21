package dev.mikoto2000.rei.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;

/** プロンプトキャッシュ計測値の結果と状態の契約を検証するテスト。 */
class PromptCacheMetricsTest {
  /** 実行時間の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void normalizesKnownUnknownAndInvalidUsage() {
    var usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(100);
    when(usage.getCacheReadInputTokens()).thenReturn(80L);
    var known = PromptCacheMetrics.usage(usage);
    assertEquals(20L, known.uncached());
    assertEquals(.8, known.hitRate());
    when(usage.getCacheReadInputTokens()).thenReturn(null);
    assertNull(PromptCacheMetrics.usage(usage).hitRate());
    when(usage.getCacheReadInputTokens()).thenReturn(101L);
    assertNull(PromptCacheMetrics.usage(usage).cached());
    when(usage.getPromptTokens()).thenReturn(null);
    assertNull(PromptCacheMetrics.usage(usage).uncached());
    assertNull(PromptCacheMetrics.usage(new EmptyUsage()).input());
  }

  /** 実行時間の把握で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void aggregatesOnlyMeasuredDenominatorsAndGroupsModels() {
    var values =
        List.of(
            new PromptCacheMetrics.Call(
                "openai-compatible",
                "a",
                "chat",
                new PromptCacheMetrics.Usage(100L, 80L),
                10L,
                20L),
            new PromptCacheMetrics.Call(
                "openai-compatible",
                "a",
                "chat",
                new PromptCacheMetrics.Usage(900L, 90L),
                30L,
                40L),
            new PromptCacheMetrics.Call(
                "UNKNOWN", "b", "summary", new PromptCacheMetrics.Usage(400L, null), null, 50L));
    var summary = PromptCacheMetrics.aggregate(values);
    assertEquals(3, summary.calls());
    assertEquals(1400L, summary.input());
    assertEquals(2, summary.cacheMeasuredCalls());
    assertEquals(830L, summary.uncached());
    assertEquals(.17, summary.hitRate());
    assertEquals(2, PromptCacheMetrics.byModel(values).size());
    assertEquals(20d, summary.ttft().mean());
    assertNull(PromptCacheMetrics.aggregate(List.of(values.getLast())).hitRate());
  }

  /** 不明を保持することを確認する。 */
  @Test
  void sdkAndProviderNeutralUsagePreserveUnknownAndExplicitZero() {
    assertEquals(
        0L, PromptCacheMetrics.usage(new DefaultUsage(100, 2, 102, null, 0L, null)).cached());
    assertNull(PromptCacheMetrics.usage(new DefaultUsage(100, 2)).cached());
    assertNull(PromptCacheMetrics.usage(new DefaultUsage(null, 2, 2, null, 30L, null)).hitRate());
  }

  /** 上限一覧を保存することを確認する。 */
  @Test
  void storeLimitsAndDisabledObservationNeverRetainPrompt() {
    var store = new TimingStore(true, 1, 1, 1, Duration.ofHours(1), Clock.systemUTC(), () -> 0L);
    store.beginRun("run", "project", "session");
    store.beginSpan("run", "call", null, null, null, TimingRecorder.Category.LLM);
    store.prompt(
        "run", "call", "chat", new org.springframework.ai.chat.prompt.Prompt("PROMPT-SECRET"));
    store.usage("run", "call", 100L, 2L, null, null);
    store.cacheUsage("run", "call", "openai-compatible", "model", 80L);
    assertFalse(store.beginSpan("run", "extra", null, null, null, TimingRecorder.Category.LLM));
    var run = store.latest("project", "session").orElseThrow();
    assertEquals(80L, run.spans().getFirst().llm().cachedInputTokens());
    assertFalse(run.toString().contains("SECRET"));
    store.beginRun("other", "project", "session");
    assertTrue(store.snapshot("project", "session", "run").isEmpty());
    assertEquals(1, store.statistics().evictedRuns());
    var disabled =
        new TimingStore(false, 1, 1, 1, Duration.ofHours(1), Clock.systemUTC(), () -> 0L);
    assertFalse(disabled.beginRun("run", "project", "session"));
  }

  /** 先行部分について、実行キー保有上限一覧を利用することを確認する。 */
  @Test
  void prefixUsesRunKeyHasLimitsAndDetectsFirstChangedRegionWithoutText() {
    var first = new PrefixDiagnostics();
    var second = new PrefixDiagnostics();
    var initial =
        List.of(
            new PrefixDiagnostics.Source("SYSTEM", "SYSTEM-SECRET"),
            new PrefixDiagnostics.Source("HISTORY", "USER-SECRET"));
    var before = first.observe(initial);
    var same = first.observe(initial);
    assertNull(same.firstChanged());
    var changed =
        first.observe(
            List.of(initial.getFirst(), new PrefixDiagnostics.Source("HISTORY", "CHANGED-SECRET")));
    assertEquals("HISTORY", changed.firstChanged());
    assertEquals(before.regions().getFirst().hash(), same.regions().getFirst().hash());
    assertNotEquals(
        before.regions().getFirst().hash(), second.observe(initial).regions().getFirst().hash());
    assertFalse(changed.toString().contains("SECRET"));
    assertTrue(
        first
                .observe(
                    java.util.stream.IntStream.range(0, 100)
                        .mapToObj(i -> new PrefixDiagnostics.Source("HISTORY", "secret"))
                        .toList())
                .regions()
                .size()
            <= 64);
  }
}
