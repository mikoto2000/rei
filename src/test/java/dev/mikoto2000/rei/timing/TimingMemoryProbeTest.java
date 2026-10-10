package dev.mikoto2000.rei.timing;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;
@org.junit.jupiter.api.Tag("integration")
class TimingMemoryProbeTest {
 @Test void measuresRepresentativeRetainedHeapAtDefaultSpanCap() throws Exception {
  var warm=new TimingStore(true,100,2000,10000,Duration.ofHours(1),Clock.systemUTC(),System::nanoTime);
  warm.beginRun("warm","project","session");warm.beginSpan("warm",UUID.randomUUID().toString(),"warm",null,null,Category.LLM);
  warm=null;System.gc();Thread.sleep(100);
  var runtime=Runtime.getRuntime();long before=runtime.totalMemory()-runtime.freeMemory();
  var store=new TimingStore(true,100,2000,10000,Duration.ofHours(1),Clock.systemUTC(),System::nanoTime);
  for(int runIndex=0;runIndex<5;runIndex++) {
   String runId=UUID.randomUUID().toString();assertTrue(store.beginRun(runId,"project","session"));
   for(int spanIndex=0;spanIndex<2000;spanIndex++) {
    String id=UUID.randomUUID().toString();assertTrue(store.beginSpan(runId,id,runId,id,"1",Category.LLM));
    store.markSpan(runId,id,Metric.FIRST_FRAMEWORK_CHUNK);store.usage(runId,id,20L,10L,null,null);store.endSpan(runId,id,Status.SUCCESS);
   }
   store.finishRun(runId,Status.SUCCESS);
  }
  System.gc();Thread.sleep(100);long after=runtime.totalMemory()-runtime.freeMemory();
  assertEquals(10000,store.statistics().spans());
  System.out.println("TIMING_MEMORY_PROBE runs=5 spans=10000 approximateRetainedBytes="+(after>before?Long.toString(after-before):"unavailable")+" heapDeltaBytes="+(after-before)+" heapBefore="+before+" heapAfter="+after+" java="+Runtime.version().feature()+" method=post-GC-process-heap-delta-not-deep-size");
  java.lang.ref.Reference.reachabilityFence(store);
 }
}
