package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@org.junit.jupiter.api.Tag("integration")
class MemoryBoundaryTest {
  @TempDir Path dir;
  @Test void partiallySpecifiedSleepConfigurationRetainsScoreDefaults() {
    var binder=new org.springframework.boot.context.properties.bind.Binder(
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("rei.memory.sleep.max-turns","3")));
    var properties=binder.bind("rei.memory",org.springframework.boot.context.properties.bind.Bindable.of(MemoryProperties.class)).get();
    assertEquals(.70,properties.sleep().minConfidence());
    assertEquals(.50,properties.sleep().minImportance());
    assertEquals(3,properties.sleep().maxTurns());
  }
  @Test void disabledSleepDoesNotReadOrExtract() {
    var repository=mock(MemoryRepository.class); var turns=mock(ConversationTurnStore.class);
    var extractor=mock(MemoryCandidateExtractor.class); var resolver=mock(MemoryResolver.class);
    var properties=new MemoryProperties(false,20,80,10,3,2000,60,null);
    assertThrows(IllegalStateException.class,()->new SleepService(repository,turns,extractor,resolver,properties).sleep("s","p",false));
    verifyNoInteractions(repository,turns,extractor,resolver);
  }
  @Test void interruptDuringPersistenceRollsBackAndRestoresInterrupt() {
    var props=new MemoryProperties(true,20,80,10,3,2000,60,null);
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db"));
    var repository=spy(new MemoryRepository(ds,new MemoryService(ds,props)));
    var turns=ConversationTurnStore.inMemory(); var owner=new AgentRunContext("turn1","s",dir,"p");
    turns.start(owner,"Vision"); turns.finish(owner,ConversationTurnStore.Status.COMPLETED,"agreed");
    doAnswer(a -> { Object result=a.callRealMethod(); Thread.currentThread().interrupt(); return result; })
        .when(repository).insert(any(),anyString(),anyString());
    var service=new SleepService(repository,turns,t -> List.of(LongTermMemoryTest.candidate("Vision",MemoryScope.PROJECT)),
        new MemoryResolver(mock(MemoryResolutionModel.class),props),props);
    try {
      assertThrows(java.util.concurrent.CancellationException.class,()->service.sleep("s","p",false));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally { Thread.interrupted(); }
    assertTrue(repository.list("p",10,0).isEmpty());
    assertEquals(0,repository.lastProcessed("s"));
  }
  @Test void batchLimitLeavesRemainderForNextManualSleep() {
    var props=new MemoryProperties(true,20,80,10,3,2000,60,null,null,new MemoryProperties.Sleep(.7,.5,1,12000,120));
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db"));
    var repository=new MemoryRepository(ds,new MemoryService(ds,props));
    var turns=ConversationTurnStore.inMemory();
    for(String id:List.of("one","two")) {
      var context=new AgentRunContext(id,"s",dir,"p"); turns.start(context,"request"); turns.finish(context,ConversationTurnStore.Status.COMPLETED,"reply");
    }
    var service=new SleepService(repository,turns,t -> List.of(),new MemoryResolver(mock(MemoryResolutionModel.class),props),props);
    assertEquals(1,service.sleep("s","p",false).run().processedTurns());
    assertEquals(1,service.unsleptTurns("s"));
    assertEquals(1,service.sleep("s","p",false).run().processedTurns());
    assertEquals(0,service.unsleptTurns("s"));
  }
  @Test void supersededAndFutureDatedMemoriesAreNotRetrieved() {
    var props=new MemoryProperties(true,20,80,10,3,2000,60,null);
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db"));
    var repository=new MemoryRepository(ds,new MemoryService(ds,props));
    var old=repository.insert(LongTermMemoryTest.candidate("Vision old",MemoryScope.GLOBAL),null,"s");
    var next=repository.insert(LongTermMemoryTest.candidate("Vision future",MemoryScope.GLOBAL),null,"s");
    repository.supersede(old.id(),next.id(),"SUPERSEDE");
    org.springframework.jdbc.core.simple.JdbcClient.create(ds).sql("UPDATE memories SET valid_from='2999-01-01T00:00:00Z' WHERE id=?").param(next.id()).update();
    assertTrue(new MemoryRetriever(repository,props).retrieve("Vision","p").memories().isEmpty());
  }
}
