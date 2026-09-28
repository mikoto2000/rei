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

class SleepServiceTest {
  @TempDir Path dir;
  MemoryRepository repository;
  ConversationTurnStore turns = ConversationTurnStore.inMemory();
  MemoryCandidateExtractor extractor = mock(MemoryCandidateExtractor.class);
  MemoryResolutionModel model = mock(MemoryResolutionModel.class);
  MemoryProperties props = new MemoryProperties(true,20,80,10,3,2000,60,null);
  SleepService service;
  @BeforeEach void setup() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"));
    repository = new MemoryRepository(ds,new MemoryService(ds,props));
    service = new SleepService(repository,turns,extractor,new MemoryResolver(model,props),props);
    when(extractor.extract(anyList())).thenReturn(List.of(LongTermMemoryTest.candidate("Vision first",MemoryScope.PROJECT)));
    when(model.resolve(any(),anyList())).thenReturn(new MemoryResolution(MemoryAction.NEW,List.of()));
  }
  void turn(String id, ConversationTurnStore.Status status) {
    var context = new AgentRunContext(id,"s",dir,"p");
    turns.start(context,"Use Vision first");
    if (status != ConversationTurnStore.Status.RUNNING) turns.finish(context,status,"agreed");
  }
  @Test void previewDoesNotWriteAndSleepIsIncremental() {
    turn("turn1",ConversationTurnStore.Status.COMPLETED);
    var original=turns.read("s");
    assertEquals(1,service.sleep("s","p",true).plans().size());
    assertEquals(0,repository.lastProcessed("s"));
    assertTrue(repository.history("p",10).isEmpty());
    assertTrue(repository.list("p",10,0).isEmpty());
    service.sleep("s","p",false);
    assertEquals(1,repository.lastProcessed("s"));
    assertEquals(1,repository.list("p",10,0).size());
    service.sleep("s","p",false);
    verify(extractor,times(2)).extract(anyList());
    assertEquals(original,turns.read("s"));
    turn("turn2",ConversationTurnStore.Status.COMPLETED);
    when(extractor.extract(anyList())).thenAnswer(a -> {
      List<ConversationTurnStore.Turn> input=a.getArgument(0);
      assertEquals(List.of("turn2"),input.stream().map(ConversationTurnStore.Turn::runId).toList());
      return List.of();
    });
    service.sleep("s","p",false);
    assertEquals(2,repository.lastProcessed("s"));
  }
  @Test void runningTurnBlocksWatermark() {
    turn("turn1",ConversationTurnStore.Status.RUNNING);
    turn("turn2",ConversationTurnStore.Status.COMPLETED);
    service.sleep("s","p",false);
    verifyNoInteractions(extractor);
    assertEquals(0,repository.lastProcessed("s"));
  }
  @Test void invalidSourceRollsBackAndRecordsFailure() {
    turn("other",ConversationTurnStore.Status.COMPLETED);
    assertThrows(IllegalArgumentException.class,()->service.sleep("s","p",false));
    assertEquals(0,repository.lastProcessed("s"));
    assertTrue(repository.list("p",10,0).isEmpty());
    assertEquals("FAILED",repository.history("p",10).getFirst().status());
  }
  @Test void cancelledExtractionNeverAdvances() {
    turn("turn1",ConversationTurnStore.Status.COMPLETED);
    when(extractor.extract(anyList())).thenThrow(new java.util.concurrent.CancellationException());
    assertThrows(java.util.concurrent.CancellationException.class,()->service.sleep("s","p",false));
    assertEquals(0,repository.lastProcessed("s"));
    assertEquals("CANCELLED",repository.history("p",10).getFirst().status());
  }
  @Test void schemaFailureInPreviewNeverWritesFailureRun() {
    turn("turn1",ConversationTurnStore.Status.COMPLETED);
    when(extractor.extract(anyList())).thenThrow(new IllegalArgumentException("schema"));
    assertThrows(IllegalArgumentException.class,()->service.sleep("s","p",true));
    assertTrue(repository.history("p",10).isEmpty());
  }
}
