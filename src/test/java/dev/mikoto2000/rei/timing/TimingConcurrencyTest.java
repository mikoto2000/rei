package dev.mikoto2000.rei.timing;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;
class TimingConcurrencyTest {
 @Test void concurrentAttemptsKeepTheirIdsAndCountersIsolated() throws Exception {
  var tick=new AtomicLong();var store=new TimingStore(true,2,500,1000,Duration.ofHours(1),Clock.systemUTC(),tick::incrementAndGet);
  assertTrue(store.beginRun("run","project","session"));
  try(var pool=Executors.newFixedThreadPool(4)) {
   var tasks=new ArrayList<Callable<Boolean>>();
   for(int index=0;index<400;index++){String id="span-"+index;tasks.add(()->store.beginSpan("run",id,"run","request",id,Category.LLM)&&store.endSpan("run",id,Status.SUCCESS));}
   for(var result:pool.invokeAll(tasks))assertTrue(result.get());
  }
  store.finishRun("run",Status.SUCCESS);var run=store.snapshot("project","session","run").orElseThrow();
  assertEquals(400,run.spans().size());assertEquals(400,run.spans().stream().map(Span::id).distinct().count());assertFalse(run.incomplete());
  assertTrue(run.summary().occupiedNanos()<=run.elapsedNanos());assertTrue(run.summary().workNanos()>=run.summary().occupiedNanos());
  assertEquals(400,store.statistics().spans());
 }
 @Test void disabledEnvironmentDoesNotParseUnusedLimits() {
  var env=new org.springframework.mock.env.MockEnvironment().withProperty("rei.timing.enabled","false").withProperty("rei.timing.max-runs","invalid-private-value");
  var store=new TimingStore(env,Clock.systemUTC());assertFalse(store.enabled());assertEquals(0,store.statistics().runs());
 }
}
