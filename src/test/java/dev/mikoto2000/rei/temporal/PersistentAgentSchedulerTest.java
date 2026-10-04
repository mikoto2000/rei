package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;

@Tag("integration")
class PersistentAgentSchedulerTest {
  @TempDir Path dir;
  final Instant now=Instant.parse("2026-10-04T03:00:00Z");
  PersistentAgentScheduler scheduler(Instant instant) {
    return new PersistentAgentScheduler(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("timers.db")),Clock.fixed(instant,ZoneOffset.UTC));
  }
  AgentRunContext owner(){return new AgentRunContext("source-run","session",dir,"project");}
  String create(PersistentAgentScheduler scheduler) {
    try(var scope=AgentRunScope.open(owner())) {return scheduler.scheduleAfter(Duration.ofSeconds(1),"Check build status","session").id();}
  }
  @Test void pendingRequiresHumanActivationAndSurvivesRestart() {
    var first=scheduler(now);String id=create(first);var restarted=scheduler(now.plusSeconds(2));
    assertEquals("PENDING",restarted.get("project",id).status());assertTrue(restarted.claimDue().isEmpty());
    restarted.activate("project",id);var claim=restarted.claimDue().orElseThrow();
    assertEquals(dir.toAbsolutePath().toString(),claim.projectRoot());assertEquals("session",claim.task().conversationId());
    assertNotEquals("source-run",claim.runId());assertTrue(first.claimDue().isEmpty());
    restarted.finish(claim,"COMPLETED","Done");assertEquals("COMPLETED",first.get("project",id).status());
    assertEquals(4,first.history("project",id).size());
  }
  @Test void exactOwnerValidationAndProjectIsolation() {
    var scheduler=scheduler(now);
    assertThrows(IllegalArgumentException.class,()->scheduler.scheduleAfter(Duration.ofSeconds(1),"x","session"));
    try(var scope=AgentRunScope.open(owner())) {
      assertThrows(IllegalArgumentException.class,()->scheduler.scheduleAfter(Duration.ofSeconds(1),"x","other"));
      assertThrows(IllegalArgumentException.class,()->scheduler.scheduleAfter(Duration.ofSeconds(-1),"x","session"));
      assertThrows(IllegalArgumentException.class,()->scheduler.scheduleAt(now.minusSeconds(1),"x","session"));
    }
    String id=create(scheduler);assertThrows(IllegalArgumentException.class,()->scheduler.activate("other",id));
    scheduler.cancel("project",id);assertThrows(IllegalStateException.class,()->scheduler.activate("project",id));
    assertTrue(scheduler.claimDue().isEmpty());
  }
  @Test void concurrentClaimsAndRestartNeverRepeatAnUncertainRun() throws Exception {
    var first=scheduler(now);String id=create(first);first.activate("project",id);
    var one=scheduler(now.plusSeconds(2));var two=scheduler(now.plusSeconds(2));
    try(var pool=Executors.newFixedThreadPool(2)) {
      var a=pool.submit(one::claimDue);var b=pool.submit(two::claimDue);
      assertEquals(1,(a.get().isPresent()?1:0)+(b.get().isPresent()?1:0));
    }
    var later=scheduler(now.plusSeconds(86400));assertEquals("RUNNING",later.get("project",id).status());
    assertTrue(later.claimDue().isEmpty());
  }
}
