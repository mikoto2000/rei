package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;

@Tag("integration")
class CronScheduleTest {
  @TempDir Path dir;
  final Instant now=Instant.parse("2026-10-04T00:00:00Z");
  PersistentAgentScheduler scheduler(Instant time) {
    return new PersistentAgentScheduler(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("cron.db")),Clock.fixed(time,ZoneOffset.UTC));
  }
  String create(PersistentAgentScheduler scheduler,String cron,String zone,int occurrences) {
    try(var scope=AgentRunScope.open(new AgentRunContext("parent","session",dir,"project"))) {
      return scheduler.scheduleCron(cron,zone,occurrences,"Check project","session").id();
    }
  }
  @Test void timezonePersistenceCoalescingAndBoundedCompletion() {
    var first=scheduler(now);String id=create(first,"0 0 10 * * *","Asia/Tokyo",2);
    assertEquals(now.plusSeconds(3600),first.get("project",id).task().executeAt());
    assertTrue(first.claimDue().isEmpty());first.activate("project",id);
    var restarted=scheduler(Instant.parse("2026-10-07T02:00:00Z"));var claim=restarted.claimDue().orElseThrow();
    assertTrue(restarted.claimDue().isEmpty());restarted.finish(claim,"COMPLETED","ok");
    assertEquals(Instant.parse("2026-10-08T01:00:00Z"),restarted.get("project",id).task().executeAt());
    assertEquals(1,restarted.cron("project",id).orElseThrow().remaining());
    assertThrows(IllegalStateException.class,()->restarted.finish(claim,"COMPLETED","stale"));
    var last=scheduler(Instant.parse("2026-10-08T01:00:00Z"));last.finish(last.claimDue().orElseThrow(),"COMPLETED","ok");
    assertEquals("COMPLETED",last.get("project",id).status());assertEquals(0,last.cron("project",id).orElseThrow().remaining());
    assertTrue(last.claimDue().isEmpty());assertThrows(IllegalArgumentException.class,()->last.cron("other",id));
  }
  @Test void invalidConfigurationLeavesNoPartialSchedule() {
    var scheduler=scheduler(now);
    for(String cron:new String[]{"bad","* * * * * *","0 0 0 31 2 *"})
      assertThrows(IllegalArgumentException.class,()->create(scheduler,cron,"UTC",2));
    assertThrows(IllegalArgumentException.class,()->create(scheduler,"0 * * * * *","Invalid/Zone",2));
    assertThrows(IllegalArgumentException.class,()->create(scheduler,"0 * * * * *","UTC",101));
    assertTrue(scheduler.list("project").isEmpty());
  }
  @Test void uncertainFailureAndCancellationNeverAutomaticallyRepeat() {
    var first=scheduler(now);String id=create(first,"0 * * * * *","UTC",3);first.activate("project",id);
    var due=scheduler(now.plusSeconds(60));var claim=due.claimDue().orElseThrow();
    var restarted=scheduler(now.plusSeconds(600));assertTrue(restarted.claimDue().isEmpty());
    restarted.reconcile("project",id,claim.runId());assertTrue(restarted.claimDue().isEmpty());
    String failed=create(restarted,"0 * * * * *","UTC",3);restarted.activate("project",failed);
    var later=scheduler(now.plusSeconds(660));later.finish(later.claimDue().orElseThrow(),"FAILED","failed");
    assertTrue(scheduler(now.plusSeconds(1200)).claimDue().isEmpty());
    String cancelled=create(later,"0 * * * * *","UTC",3);later.activate("project",cancelled);later.cancel("project",cancelled);
    assertTrue(scheduler(now.plusSeconds(1200)).claimDue().isEmpty());
  }
  @Test void localWallClockHandlesSpringDstGap() {
    var scheduler=scheduler(Instant.parse("2026-03-07T08:00:00Z"));
    String id=create(scheduler,"0 30 2 * * *","America/New_York",2);
    assertEquals(Instant.parse("2026-03-09T06:30:00Z"),scheduler.get("project",id).task().executeAt());
  }
  @Test void toolRegistrationKeepsExactSessionOwnershipAndPendingState() {
    var scheduler=scheduler(now);var tools=new SchedulerTools(scheduler);
    try(var scope=AgentRunScope.open(new AgentRunContext("parent","session",dir,"project"))) {
      assertThrows(IllegalArgumentException.class,()->tools.scheduleCron("0 * * * * *","UTC",2,"Check","other"));
      var task=tools.scheduleCron("0 * * * * *","UTC",2,"Check","session");
      assertEquals("PENDING",scheduler.get("project",task.id()).status());assertTrue(scheduler.claimDue().isEmpty());
    }
  }
}
