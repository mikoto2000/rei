package dev.mikoto2000.rei.timing;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PromptCacheMetricsTest {
 @Test void normalizesKnownUnknownAndInvalidUsage() {
  var usage=mock(Usage.class);when(usage.getPromptTokens()).thenReturn(100);when(usage.getCacheReadInputTokens()).thenReturn(80L);
  var known=PromptCacheMetrics.usage(usage);assertEquals(20L,known.uncached());assertEquals(.8,known.hitRate());
  when(usage.getCacheReadInputTokens()).thenReturn(null);assertNull(PromptCacheMetrics.usage(usage).hitRate());
  when(usage.getCacheReadInputTokens()).thenReturn(101L);assertNull(PromptCacheMetrics.usage(usage).cached());
  when(usage.getPromptTokens()).thenReturn(null);assertNull(PromptCacheMetrics.usage(usage).uncached());
  assertNull(PromptCacheMetrics.usage(new EmptyUsage()).input());
 }
 @Test void aggregatesOnlyMeasuredDenominatorsAndGroupsModels() {
  var values=List.of(new PromptCacheMetrics.Call("openai-compatible","a","chat",new PromptCacheMetrics.Usage(100L,80L),10L,20L),
    new PromptCacheMetrics.Call("openai-compatible","a","chat",new PromptCacheMetrics.Usage(900L,90L),30L,40L),
    new PromptCacheMetrics.Call("UNKNOWN","b","summary",new PromptCacheMetrics.Usage(400L,null),null,50L));
  var summary=PromptCacheMetrics.aggregate(values);assertEquals(3,summary.calls());assertEquals(1400L,summary.input());
  assertEquals(2,summary.cacheMeasuredCalls());assertEquals(830L,summary.uncached());assertEquals(.17,summary.hitRate());
  assertEquals(2,PromptCacheMetrics.byModel(values).size());assertEquals(20d,summary.ttft().mean());
  assertNull(PromptCacheMetrics.aggregate(List.of(values.getLast())).hitRate());
 }
 @Test void sdkAndProviderNeutralUsagePreserveUnknownAndExplicitZero() {
  assertEquals(0L,PromptCacheMetrics.usage(new DefaultUsage(100,2,102,null,0L,null)).cached());
  assertNull(PromptCacheMetrics.usage(new DefaultUsage(100,2)).cached());
  assertNull(PromptCacheMetrics.usage(new DefaultUsage(null,2,2,null,30L,null)).hitRate());
 }
 @Test void storeLimitsAndDisabledObservationNeverRetainPrompt() {
  var store=new TimingStore(true,1,1,1,Duration.ofHours(1),Clock.systemUTC(),()->0L);
  store.beginRun("run","project","session");store.beginSpan("run","call",null,null,null,TimingRecorder.Category.LLM);
  store.prompt("run","call","chat",new org.springframework.ai.chat.prompt.Prompt("PROMPT-SECRET"));
  store.usage("run","call",100L,2L,null,null);store.cacheUsage("run","call","openai-compatible","model",80L);
  assertFalse(store.beginSpan("run","extra",null,null,null,TimingRecorder.Category.LLM));
  var run=store.latest("project","session").orElseThrow();assertEquals(80L,run.spans().getFirst().llm().cachedInputTokens());assertFalse(run.toString().contains("SECRET"));
  store.beginRun("other","project","session");assertTrue(store.snapshot("project","session","run").isEmpty());assertEquals(1,store.statistics().evictedRuns());
  var disabled=new TimingStore(false,1,1,1,Duration.ofHours(1),Clock.systemUTC(),()->0L);assertFalse(disabled.beginRun("run","project","session"));
 }
 @Test void prefixUsesRunKeyHasLimitsAndDetectsFirstChangedRegionWithoutText() {
  var first=new PrefixDiagnostics();var second=new PrefixDiagnostics();
  var initial=List.of(new PrefixDiagnostics.Source("SYSTEM","SYSTEM-SECRET"),new PrefixDiagnostics.Source("HISTORY","USER-SECRET"));
  var before=first.observe(initial);var same=first.observe(initial);assertNull(same.firstChanged());
  var changed=first.observe(List.of(initial.getFirst(),new PrefixDiagnostics.Source("HISTORY","CHANGED-SECRET")));
  assertEquals("HISTORY",changed.firstChanged());assertEquals(before.regions().getFirst().hash(),same.regions().getFirst().hash());
  assertNotEquals(before.regions().getFirst().hash(),second.observe(initial).regions().getFirst().hash());
  assertFalse(changed.toString().contains("SECRET"));
  assertTrue(first.observe(java.util.stream.IntStream.range(0,100).mapToObj(i->new PrefixDiagnostics.Source("HISTORY","secret")).toList()).regions().size()<=64);
 }
}

