package dev.mikoto2000.rei.goal;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
class GoalRecoveryTest {
  @TempDir Path dir;
  GoalRepository repository;
  @BeforeEach void setup(){repository=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goals.db")),Clock.systemUTC());}
  GoalRepository.Goal goal(){return repository.create(new AgentRunContext("source","session",dir,"project"),"artifact","out.txt","a".repeat(64),3,2);}
  @Test void explicitReconciliationAfterRestartPreservesBudgetAndRevokesOldClaim() {
    var goal=goal();var claim=repository.claim("project",goal.id());String run=repository.beginAttempt(claim);assertTrue(repository.reserveLlm(claim));
    var restarted=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goals.db")),Clock.systemUTC());
    var recovered=restarted.reconcile("project",goal.id(),run);assertEquals("PAUSED",recovered.status());assertEquals(1,recovered.attempts());assertEquals(1,recovered.llmCallsUsed());
    assertEquals("BLOCKED",restarted.attempts("project",goal.id()).getFirst().status());assertFalse(repository.reserveLlm(claim));
    assertThrows(IllegalStateException.class,()->repository.stop(claim,"COMPLETED","file_digest_verified"));
    var resumed=restarted.claim("project",goal.id());assertNotEquals(claim.token(),resumed.token());assertTrue(restarted.reserveLlm(resumed));assertFalse(restarted.reserveLlm(resumed));
  }
  @Test void staleRunAndWrongProjectCannotReconcile() {
    var goal=goal();var claim=repository.claim("project",goal.id());String run=repository.beginAttempt(claim);
    assertThrows(IllegalStateException.class,()->repository.reconcile("project",goal.id(),"stale"));
    assertThrows(IllegalArgumentException.class,()->repository.reconcile("other",goal.id(),run));assertTrue(repository.active(claim));
  }
  @Test void claimWithoutAttemptCanBeReconciledButTerminalGoalCannot() {
    var goal=goal();repository.claim("project",goal.id());assertEquals("PAUSED",repository.reconcile("project",goal.id(),null).status());
    assertThrows(IllegalStateException.class,()->repository.reconcile("project",goal.id(),null));
  }
}
