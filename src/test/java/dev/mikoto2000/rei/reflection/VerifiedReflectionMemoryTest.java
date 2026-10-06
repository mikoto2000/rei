package dev.mikoto2000.rei.reflection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.goal.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;

@Tag("integration")
class VerifiedReflectionMemoryTest {
  @TempDir Path dir;
  Clock clock=Clock.fixed(Instant.parse("2026-10-06T14:00:00Z"),ZoneOffset.UTC);
  MemoryProperties props=new MemoryProperties(true,20,80,10,3,2000,60,null);
  DriverManagerDataSource source;GoalRepository goals;GoalReflectionRepository reflections;GoalReflectionService collect;MemoryRepository memories;
  GoalLoopService.Gateway gateway;VerifiedReflectionMemoryService service;
  @BeforeEach void setup(){source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("proof.db"));goals=new GoalRepository(source,clock);reflections=new GoalReflectionRepository(source,clock);collect=new GoalReflectionService(goals,reflections,new InMemoryAgentEventBus(),clock);memories=new MemoryRepository(source,new MemoryService(source,props,clock));gateway=mock(GoalLoopService.Gateway.class);service=new VerifiedReflectionMemoryService(goals,reflections,memories,new FileGoalVerifier(),gateway,props,clock);}
  GoalRepository.Goal completed() throws Exception {Files.writeString(dir.resolve("out.txt"),"verified content");String digest=new FileGoalVerifier().fingerprint(dir.toRealPath(),"out.txt").sha256();var goal=goals.create(new AgentRunContext("source","session",dir.toRealPath(),"project"),"secret model objective","out.txt",digest,3,5);return goals.verifiedWithoutRun("project",goal.id());}
  @Test void independentlyVerifiedFactHasPersistentProofAndNoFabricatedConversationTurn() throws Exception {
    var goal=completed();var reflection=collect.collect("project",goal.id());var promotion=service.promote("project",reflection.id());var memory=promotion.memory();
    assertEquals(MemoryScope.PROJECT,memory.scope());assertEquals(MemoryType.PROJECT_STATE,memory.type());assertEquals("project",memory.projectId());assertTrue(memory.content().contains("2026-10-06T14:00:00Z"));assertTrue(memory.content().contains(goal.sha256()));assertFalse(memory.content().contains("secret model objective"));assertTrue(memory.sources().isEmpty());
    assertEquals(reflection.id(),promotion.proof().reflectionId());assertEquals(goal.id(),promotion.proof().goalId());assertEquals("session",promotion.proof().sessionId());assertEquals(memory.id(),promotion.proof().memoryId());assertEquals(64,promotion.proof().criteriaSha256().length());
    var reopened=new MemoryRepository(source,new MemoryService(source,props,clock));assertEquals(promotion.proof(),reopened.verifiedReflectionProof("project",reflection.id()).orElseThrow());assertEquals(1,reopened.search("out.txt","project",10).size());assertTrue(reopened.search("out.txt","other",10).isEmpty());verify(gateway).validate(goal);verify(gateway,never()).dispatch(any(),anyString(),any());assertEquals(0,goals.get("project",goal.id()).attempts());
  }
  @Test void changedArtifactUnverifiedOrForeignReflectionCannotBecomeKnowledge() throws Exception {
    var goal=completed();var reflection=collect.collect("project",goal.id());Files.writeString(dir.resolve("out.txt"),"changed");assertThrows(IllegalStateException.class,()->service.promote("project",reflection.id()));assertTrue(memories.list("project",10,0).isEmpty());assertThrows(IllegalArgumentException.class,()->service.promote("other",reflection.id()));
    var failed=goals.create(new AgentRunContext("run","session",dir,"project"),"failed","missing.txt","a".repeat(64),1,1);var claim=goals.claim("project",failed.id());goals.stop(claim,"BLOCKED","budget_exhausted");var unknown=collect.collect("project",failed.id());assertThrows(IllegalArgumentException.class,()->service.promote("project",unknown.id()));assertTrue(memories.list("project",10,0).isEmpty());
  }
  @Test void repeatedPromotionKeepsHistoricalFactAndNeverResurrectsArchivedMemory() throws Exception {
    var goal=completed();var reflection=collect.collect("project",goal.id());var first=service.promote("project",reflection.id());Files.writeString(dir.resolve("out.txt"),"later edit");assertEquals(first,service.promote("project",reflection.id()));assertEquals(1,memories.list("project",10,0).size());memories.archive(first.memory().id());var repeated=service.promote("project",reflection.id());assertEquals(MemoryStatus.ARCHIVED,repeated.memory().status());assertTrue(memories.list("project",10,0).isEmpty());
  }
  @Test void jsonCriteriaAndMultipleFilesAreVerifiedBeforePromotion() throws Exception {
    Files.writeString(dir.resolve("result.json"),"{\"ok\":true,\"count\":1}");Files.writeString(dir.resolve("out.txt"),"content");var criteria=List.of(new GoalRepository.FileCriterion("result.json","","/ok","true"),new GoalRepository.FileCriterion("result.json","","/count","1.0"),new GoalRepository.FileCriterion("out.txt",new FileGoalVerifier().fingerprint(dir.toRealPath(),"out.txt").sha256()));
    var goal=goals.create(new AgentRunContext("run","session",dir.toRealPath(),"project"),"JSON",criteria,1,2);goals.verifiedWithoutRun("project",goal.id());var reflection=collect.collect("project",goal.id());var promoted=service.promote("project",reflection.id());assertTrue(promoted.memory().content().contains("/ok"));assertTrue(promoted.memory().content().contains("/count"));assertTrue(promoted.memory().content().contains("out.txt"));
  }
  @Test void memoryDisableOwnershipValidationAndCancellationCannotWriteFacts() throws Exception {
    var goal=completed();var reflection=collect.collect("project",goal.id());var disabled=new VerifiedReflectionMemoryService(goals,reflections,memories,new FileGoalVerifier(),gateway,new MemoryProperties(false,20,80,10,3,2000,60,null),clock);assertThrows(IllegalStateException.class,()->disabled.promote("project",reflection.id()));
    doThrow(new IllegalArgumentException("owner unavailable")).when(gateway).validate(any());assertThrows(IllegalArgumentException.class,()->service.promote("project",reflection.id()));reset(gateway);
    try{Thread.currentThread().interrupt();assertThrows(IllegalStateException.class,()->service.promote("project",reflection.id()));}finally{Thread.interrupted();}assertTrue(memories.list("project",10,0).isEmpty());
  }
  @Test void concurrentPromotionPublishesOnlyOneMemoryAndProof() throws Exception {
    var goal=completed();var reflection=collect.collect("project",goal.id());var gate=new java.util.concurrent.CountDownLatch(1);var ids=new HashSet<String>();
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {var futures=new ArrayList<java.util.concurrent.Future<VerifiedReflectionMemoryService.Promotion>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{gate.await();return service.promote("project",reflection.id());}));gate.countDown();for(var future:futures)ids.add(future.get().memory().id());}
    assertEquals(1,ids.size());assertEquals(1,memories.list("project",10,0).size());assertTrue(memories.verifiedReflectionProof("project",reflection.id()).isPresent());
  }
  @Test void insertionFailureRollsBackProofAndAllowsALaterExplicitRetry() throws Exception {
    var goal=completed();var reflection=collect.collect("project",goal.id());var db=org.springframework.jdbc.core.simple.JdbcClient.create(source);db.sql("CREATE TRIGGER fixture_reject_memory BEFORE INSERT ON memories BEGIN SELECT RAISE(ABORT,'fixture'); END").update();
    assertThrows(RuntimeException.class,()->service.promote("project",reflection.id()));assertTrue(memories.verifiedReflectionProof("project",reflection.id()).isEmpty());assertTrue(memories.list("project",10,0).isEmpty());db.sql("DROP TRIGGER fixture_reject_memory").update();assertNotNull(service.promote("project",reflection.id()).memory());
  }
  @Test void forgedCompletionAndChangedSavedCriteriaCannotSupplyPromotionEvidence() throws Exception {
    Files.writeString(dir.resolve("out.txt"),"exists");var ready=goals.create(new AgentRunContext("run","session",dir,"project"),"objective","out.txt","a".repeat(64),1,1);var snapshot=new GoalLifecyclePayload(ready.id(),"COMPLETED",0,1,0,1,"file_digest_verified");var forged=reflections.save(ready,new AgentEvent("forged",0,clock.instant(),AgentEventType.GOAL_UPDATED,1,"session",null,null,null,null,snapshot,"project"),snapshot,"VERIFIED","file_digest_verified","CRITERION_SATISFIED","model lesson");assertThrows(IllegalArgumentException.class,()->service.promote("project",forged.id()));
    var goal=completed();var reflection=collect.collect("project",goal.id());org.springframework.jdbc.core.simple.JdbcClient.create(source).sql("UPDATE agent_goals SET digest=? WHERE id=?").params("b".repeat(64),goal.id()).update();assertThrows(IllegalArgumentException.class,()->service.promote("project",reflection.id()));assertTrue(memories.list("project",10,0).isEmpty());
  }
  @Test void shellPromotesExplicitlyAndMemoryShowDistinguishesReflectionProofFromTurns() throws Exception {
    var goal=completed();var reflection=collect.collect("project",goal.id());var projects=mock(dev.mikoto2000.rei.core.project.ProjectService.class);var project=mock(dev.mikoto2000.rei.core.project.ProjectContext.class);when(project.id()).thenReturn("project");when(projects.currentContext()).thenReturn(project);
    var command=new ReflectionCommand(reflections,collect,projects);command.setPromotion(service);var text=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(text));var cli=new picocli.CommandLine(command);assertEquals(0,cli.execute("promote",reflection.id()));var proof=memories.verifiedReflectionProof("project",reflection.id()).orElseThrow();var support=new dev.mikoto2000.rei.memory.command.MemoryCommandSupport(memories,null,projects,props);String shown=support.show(proof.memoryId());assertTrue(shown.contains("source sessions/turns: []"));assertTrue(shown.contains("verified reflection proof:"));assertTrue(shown.contains(reflection.id()));when(project.id()).thenReturn("other");assertEquals(2,cli.execute("promote",reflection.id()));assertThrows(IllegalArgumentException.class,()->support.show(proof.memoryId()));
  }
  @Test void oversizedCriteriaFailWithoutTruncatingEvidenceOrSavingAPartialProof() throws Exception {
    var values=new LinkedHashMap<String,String>();var criteria=new ArrayList<GoalRepository.FileCriterion>();for(int i=0;i<6;i++){values.put("value"+i,"x".repeat(700));criteria.add(new GoalRepository.FileCriterion("large.json","","/value"+i,"\""+"x".repeat(700)+"\""));}Files.writeString(dir.resolve("large.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values));var goal=goals.create(new AgentRunContext("run","session",dir.toRealPath(),"project"),"large",criteria,1,1);goals.verifiedWithoutRun("project",goal.id());var reflection=collect.collect("project",goal.id());assertThrows(IllegalArgumentException.class,()->service.promote("project",reflection.id()));assertTrue(memories.verifiedReflectionProof("project",reflection.id()).isEmpty());assertTrue(memories.list("project",10,0).isEmpty());
  }
}
