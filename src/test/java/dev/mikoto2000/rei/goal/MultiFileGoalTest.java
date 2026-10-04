package dev.mikoto2000.rei.goal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.junit.jupiter.api.Assertions.*;
class MultiFileGoalTest {
  @TempDir Path root;
  GoalRepository repository;
  String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  AgentRunContext owner(){return new AgentRunContext("run","session",root,"project");}
  @BeforeEach void setup(){repository=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());}
  @Test void persistsAllCriteriaAndRequiresEveryFileAfterRestart() throws Exception {
    var criteria=List.of(new GoalRepository.FileCriterion("A.txt",hash("a")),new GoalRepository.FileCriterion("B.txt",hash("b")));
    var goal=repository.create(owner(),"two artifacts",criteria,3,10);Files.writeString(root.resolve("A.txt"),"a");
    assertFalse(new FileGoalVerifier().verify(goal).satisfied());Files.writeString(root.resolve("B.txt"),"b");
    var restarted=new GoalRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());
    var saved=restarted.get("project",goal.id());assertEquals(criteria,saved.criteria());assertTrue(new FileGoalVerifier().verify(saved).satisfied());
    Files.writeString(root.resolve("B.txt"),"changed");assertFalse(new FileGoalVerifier().verify(saved).satisfied());
  }
  @Test void invalidDuplicateOrUnboundedCriteriaLeaveNoGoal() throws Exception {
    var valid=new GoalRepository.FileCriterion("A.txt",hash("a"));
    assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x",List.of(valid,new GoalRepository.FileCriterion("./A.txt",hash("b"))),3,10));
    assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x",List.of(valid,new GoalRepository.FileCriterion("../escape",hash("b"))),3,10));
    assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x",Collections.nCopies(17,valid),3,10));
    assertThrows(IllegalArgumentException.class,()->repository.create(owner(),"x",List.of(),3,10));assertTrue(repository.list("project").isEmpty());
  }
  @Test void legacySingleFileCriteriaAndBudgetBehaviorRemainCompatible() throws Exception {
    var goal=repository.create(owner(),"x","A.txt",hash("a"),3,1);
    assertEquals(List.of(new GoalRepository.FileCriterion("A.txt",hash("a"))),goal.criteria());
    var claim=repository.claim("project",goal.id());assertTrue(repository.reserveLlm(claim));assertFalse(repository.reserveLlm(claim));
  }
  @Test void reflectionPreservesEveryExpectedFileAndVerificationFacts() throws Exception {
    var criteria=List.of(new GoalRepository.FileCriterion("A.txt",hash("a")),new GoalRepository.FileCriterion("B.txt",hash("b")));
    var goal=repository.create(owner(),"both",criteria,3,10);repository.verifiedWithoutRun("project",goal.id());
    var records=new dev.mikoto2000.rei.reflection.GoalReflectionRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("goals.db")),Clock.systemUTC());
    var service=new dev.mikoto2000.rei.reflection.GoalReflectionService(repository,records,org.mockito.Mockito.mock(dev.mikoto2000.rei.event.AgentEventBus.class),Clock.systemUTC());
    var item=service.collect("project",goal.id());assertEquals("VERIFIED",item.actual());
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    assertEquals(List.of("A.txt","B.txt"),mapper.readValue(item.expectedFile(),List.class));
    assertEquals(List.of(hash("a"),hash("b")),mapper.readValue(item.expectedSha256(),List.class));
  }
}
