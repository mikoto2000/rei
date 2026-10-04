package dev.mikoto2000.rei.temporal;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
class ScheduleRecoveryTest {
  @TempDir Path dir;
  final Instant now=Instant.parse("2026-10-04T03:00:00Z");
  PersistentAgentScheduler scheduler(){return new PersistentAgentScheduler(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("timers.db")),Clock.fixed(now,ZoneOffset.UTC));}
  String create(PersistentAgentScheduler scheduler){try(var scope=AgentRunScope.open(new AgentRunContext("source","session",dir,"project"))){var task=scheduler.scheduleAfter(Duration.ZERO,"inspect","session");scheduler.activate("project",task.id());return task.id();}}
  @Test void restartReconciliationEndsUncertainClaimWithoutReplayingAndUnblocksOtherSchedule(){
    var original=scheduler();String first=create(original);var claim=original.claimDue().orElseThrow();String second=create(original);assertTrue(original.claimDue().isEmpty());
    var restarted=scheduler();var result=restarted.reconcile("project",first,claim.runId());assertEquals("FAILED",result.status());assertEquals("uncertain_run_reconciled",result.outcome());
    assertEquals(claim.runId(),result.runId());assertEquals(second,restarted.claimDue().orElseThrow().task().id());
    assertThrows(IllegalStateException.class,()->original.finish(claim,"COMPLETED","late"));assertThrows(IllegalStateException.class,()->restarted.activate("project",first));
    assertEquals("FAILED",restarted.history("project",first).getLast().status());
  }
  @Test void wrongProjectStaleRunAndTerminalStateAreRejected(){
    var scheduler=scheduler();String id=create(scheduler);var claim=scheduler.claimDue().orElseThrow();
    assertThrows(IllegalArgumentException.class,()->scheduler.reconcile("other",id,claim.runId()));assertThrows(IllegalStateException.class,()->scheduler.reconcile("project",id,"stale"));
    scheduler.reconcile("project",id,claim.runId());assertThrows(IllegalStateException.class,()->scheduler.reconcile("project",id,claim.runId()));
  }
}
