package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

class MemoryConsolidationTest {
  @TempDir Path dir;
  MemoryRepository repository;
  DriverManagerDataSource ds;
  ConversationTurnStore turns=ConversationTurnStore.inMemory();
  MemoryProperties props=new MemoryProperties(true,20,80,10,3,2000,60,null);
  MemoryResolutionModel model=mock(MemoryResolutionModel.class);
  MemoryResolver resolver;
  @BeforeEach void setup() {
    ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"));
    repository=new MemoryRepository(ds,new MemoryService(ds,props));
    resolver=new MemoryResolver(model,props);
    var context=new AgentRunContext("turn1","s",dir,"p");
    turns.start(context,"Vision first"); turns.finish(context,ConversationTurnStore.Status.COMPLETED,"agreed");
  }
  LongTermMemory existing(String text) { return repository.insert(LongTermMemoryTest.candidate(text,MemoryScope.PROJECT),"p","old"); }
  SleepService.Report run(String text,MemoryAction action,List<String> targets) {
    when(model.resolve(any(),anyList())).thenReturn(new MemoryResolution(action,targets));
    return new SleepService(repository,turns,t -> List.of(LongTermMemoryTest.candidate(text,MemoryScope.PROJECT)),resolver,props).sleep("s","p",false);
  }
  @Test void duplicateDoesNotCreateRowAndAddsSource() {
    var old=existing("Vision first");
    run("Vision first",MemoryAction.NEW,List.of());
    verifyNoInteractions(model);
    assertEquals(1,repository.list("p",10,0).size());
    assertEquals(2,repository.find(old.id()).orElseThrow().sources().size());
  }
  @Test void exactDuplicateOutsideSearchWindowStillUsesOriginal() {
    var old=existing("Vision first");
    for(int i=0;i<60;i++) existing("Vision alternative "+i);
    run("Vision first",MemoryAction.NEW,List.of());
    assertEquals(61,repository.list("p",100,0).size());
    assertEquals(2,repository.find(old.id()).orElseThrow().sources().size());
    verifyNoInteractions(model);
  }
  @Test void updatePreservesIdentityAndOriginalSource() {
    var old=existing("Vision first");
    run("Vision first with fallback",MemoryAction.UPDATE,List.of(old.id()));
    var updated=repository.find(old.id()).orElseThrow();
    assertEquals("Vision first with fallback",updated.content());
    assertEquals(old.createdAt(),updated.createdAt());
    assertEquals(2,updated.sources().size());
    assertEquals(1,repository.search("fallback","p",10).size());
  }
  @Test void supersedeRetainsOriginalAndLinksNewDecision() {
    var old=existing("Vision disabled");
    run("Vision enabled",MemoryAction.SUPERSEDE,List.of(old.id()));
    var replaced=repository.find(old.id()).orElseThrow();
    assertEquals(MemoryStatus.SUPERSEDED,replaced.status());
    assertNotNull(replaced.supersededBy());
    assertNotNull(replaced.validUntil());
    assertEquals(1,repository.search("Vision","p",10).size());
  }
  @Test void mergeCombinesSourcesAndSupersedesBoth() {
    var a=existing("Vision Windows"); var b=existing("Vision voice input");
    run("Vision Windows voice input",MemoryAction.MERGE,List.of(a.id(),b.id()));
    var active=repository.list("p",10,0);
    assertEquals(1,active.size());
    assertEquals(2,active.getFirst().sources().size());
    assertEquals(2,repository.relations(active.getFirst().id()).size());
  }
  @Test void conflictPreservesBothButDoesNotInjectUnresolvedClaims() {
    var old=existing("Vision disabled");
    var report=run("Vision enabled",MemoryAction.CONFLICT,List.of(old.id()));
    assertEquals(1,report.run().conflicts());
    assertEquals(MemoryStatus.ACTIVE,repository.find(old.id()).orElseThrow().status());
    assertEquals(2,repository.list("p",10,0).size());
    assertTrue(new MemoryRetriever(repository,props).retrieve("Vision","p").memories().isEmpty());
  }
  @Test void thresholdIgnoresLowValueAndRejectsOutOfScopeResolutionTargets() {
    var low=new MemoryCandidate(MemoryType.FACT,MemoryScope.GLOBAL,"Vision","Vision",.2,.9,List.of("turn1"),List.of());
    assertEquals(MemoryAction.IGNORE,resolver.resolve(low,List.of(),"p").action());
    var old=existing("Vision disabled");
    when(model.resolve(any(),anyList())).thenReturn(new MemoryResolution(MemoryAction.UPDATE,List.of("foreign")));
    assertThrows(IllegalArgumentException.class,()->resolver.resolve(LongTermMemoryTest.candidate("Vision enabled",MemoryScope.PROJECT),List.of(old),"p"));
  }
  @Test void rollbackIncludesMemoriesAndCheckpointWhenRunInsertFails() {
    JdbcClient.create(ds).sql("CREATE TRIGGER fail_run BEFORE INSERT ON sleep_runs BEGIN SELECT RAISE(ABORT,'injected'); END").update();
    assertThrows(RuntimeException.class,()->run("Vision new",MemoryAction.NEW,List.of()));
    assertTrue(repository.list("p",10,0).isEmpty());
    assertEquals(0,repository.lastProcessed("s"));
  }
  @Test void persistenceSurvivesRepositoryRestartAndKeepsLegacyData() {
    var old=existing("Vision persistent");
    var legacy=new MemoryService(ds,props);
    var legacyRow=legacy.save(new Memory(null,"legacy",MemoryType.KNOWLEDGE,MemoryScope.SHORT_TERM,MemoryStatus.ACTIVE,.9,null,null,null));
    var reopened=new MemoryRepository(ds,legacy);
    assertEquals(old,reopened.find(old.id()).orElseThrow());
    assertTrue(legacy.findById(legacyRow.id()).isPresent());
    assertEquals(1,reopened.list("p",20,0).size());
  }
  @Test void sameSessionConcurrentSleepIsRejected() throws Exception {
    var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
    MemoryCandidateExtractor extractor=t -> {
      entered.countDown();
      try { if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
      catch(InterruptedException e) { throw new CancellationException(); }
      return List.of();
    };
    var service=new SleepService(repository,turns,extractor,resolver,props);
    try(var executor=Executors.newSingleThreadExecutor()) {
      var first=executor.submit(()->service.sleep("s","p",false));
      try {
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class,()->service.sleep("s","p",false));
      } finally { release.countDown(); }
      first.get(5,TimeUnit.SECONDS);
    }
    assertEquals(1,repository.history("p",10).size());
  }
}
