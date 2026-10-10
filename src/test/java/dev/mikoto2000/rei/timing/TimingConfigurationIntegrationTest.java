package dev.mikoto2000.rei.timing;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;
import static org.junit.jupiter.api.Assertions.*;
@org.junit.jupiter.api.Tag("integration")
class TimingConfigurationIntegrationTest {
 @Test void foundationStartsWithoutRequestCaptureOrModelInfrastructure() {
  new org.springframework.boot.test.context.runner.ApplicationContextRunner()
   .withBean(Clock.class,Clock::systemUTC).withUserConfiguration(TimingStore.class)
   .withPropertyValues("rei.timing.enabled=true","rei.timing.max-runs=2","rei.timing.max-spans-per-run=3","rei.timing.max-total-spans=6","rei.timing.ttl=2s")
   .run(context->{assertNull(context.getStartupFailure());var recorder=context.getBean(TimingRecorder.class);assertTrue(recorder.enabled());assertTrue(recorder.beginRun("run","project","session"));assertEquals(1,recorder.statistics().runs());});
 }
 @Test void lateHumanApprovalIsClippedToRunOccupancyWithoutLosingItsActualEnd() {
  var tick=new AtomicLong();var store=new TimingStore(true,2,3,6,Duration.ofHours(1),Clock.systemUTC(),tick::get);
  store.beginRun("run","project","session");tick.set(1);store.beginSpan("run","approval","run","request",null,Category.APPROVAL_WAIT);
  tick.set(3);store.finishRun("run",Status.SUCCESS);assertTrue(store.snapshot("project","session","run").orElseThrow().incomplete());
  tick.set(20);assertTrue(store.endSpan("run","approval",Status.SUCCESS));var run=store.snapshot("project","session","run").orElseThrow();
  assertEquals(3,run.elapsedNanos());assertEquals(2,run.summary().occupiedNanos());assertEquals(20,run.spans().getFirst().endNanos());assertFalse(run.incomplete());
 }
 @Test void invalidAndForeignParentIdsDoNotEnterMetadata() {
  var store=new TimingStore(true,2,3,6,Duration.ofHours(1),Clock.systemUTC(),System::nanoTime);
  store.beginRun("run","project","session");assertFalse(store.beginSpan("run","invalid/path",null,null,null,Category.TOOL));
  assertFalse(store.beginSpan("run","span","foreign-parent",null,null,Category.TOOL));
  assertEquals(0,store.statistics().spans());assertTrue(store.snapshot("project","session","run").orElseThrow().incomplete());
 }
}
