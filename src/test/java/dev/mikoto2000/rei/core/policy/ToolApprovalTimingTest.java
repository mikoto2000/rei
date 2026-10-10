package dev.mikoto2000.rei.core.policy;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.timing.*;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("integration")
class ToolApprovalTimingTest {
 @TempDir Path dir;
 @Test void humanDecisionAfterRunEndClipsOccupancyAndDoesNotCopyArguments() {
  var tick=new AtomicLong();var clock=Clock.systemUTC();var store=new TimingStore(true,10,20,100,Duration.ofHours(1),clock,tick::get);
  var repository=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approval.db")),clock);
  repository.setTiming(store);var owner=new AgentRunContext("run","session",dir,"project");
  store.beginRun("run","project","session");tick.set(2);var request=repository.request("custom","ARGUMENT-SECRET",owner);
  tick.set(5);store.finishRun("run",Status.SUCCESS);assertTrue(store.latest("project","session").orElseThrow().incomplete());
  tick.set(30);repository.decide("project",request.id(),true);var run=store.latest("project","session").orElseThrow();
  assertEquals(30,run.spans().getFirst().endNanos());assertEquals(3,run.summary().occupancyNanos().get(Category.APPROVAL_WAIT));assertFalse(run.toString().contains("SECRET"));assertFalse(run.incomplete());
 }
 @Test void repeatedRequestAndForeignProjectDoNotCreateOrFinishAnotherSpan() {
  var tick=new AtomicLong();var clock=Clock.systemUTC();var store=new TimingStore(true,10,20,100,Duration.ofHours(1),clock,tick::get);
  var repository=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approval.db")),clock);repository.setTiming(store);
  var owner=new AgentRunContext("run","session",dir,"project");store.beginRun("run","project","session");var request=repository.request("custom","{}",owner);repository.request("custom","{}",owner);
  assertEquals(1,store.statistics().spans());assertThrows(IllegalArgumentException.class,()->repository.decide("foreign",request.id(),false));assertEquals(Status.INCOMPLETE,store.latest("project","session").orElseThrow().spans().getFirst().status());
 }
}
