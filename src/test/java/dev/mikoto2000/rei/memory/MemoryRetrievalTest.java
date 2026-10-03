package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;

@org.junit.jupiter.api.Tag("integration")
class MemoryRetrievalTest {
  @TempDir Path dir;
  MemoryRepository repository;
  MemoryProperties properties=new MemoryProperties(true,20,80,10,3,2000,60,null);
  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("m.db"));
    repository=new MemoryRepository(ds,new MemoryService(ds,properties));
  }
  @Test void selectsGlobalAndCurrentActiveOnlyWithinBudget() {
    var global=repository.insert(LongTermMemoryTest.candidate("Vision global",MemoryScope.GLOBAL),null,"s");
    var project=repository.insert(LongTermMemoryTest.candidate("Vision project",MemoryScope.PROJECT),"p","s");
    repository.insert(LongTermMemoryTest.candidate("Vision private",MemoryScope.PROJECT),"other","s");
    var archived=repository.insert(LongTermMemoryTest.candidate("Vision archived",MemoryScope.GLOBAL),null,"s");
    repository.archive(archived.id());
    var result=new MemoryRetriever(repository,properties).retrieve("Vision","p");
    assertEquals(2,result.memories().size());
    assertEquals(project.id(),result.memories().getFirst().id());
    assertTrue(result.context().contains(global.content()));
    assertFalse(result.context().contains("private"));
    assertTrue(TokenEstimator.conservative().text(result.context())+8<=1500);
    assertNotNull(repository.find(global.id()).orElseThrow().lastAccessedAt());
  }
  @Test void respectsCountAndTinyTokenBudget() {
    for(int i=0;i<8;i++) repository.insert(LongTermMemoryTest.candidate("Vision "+i,MemoryScope.GLOBAL),null,"s");
    assertEquals(5,new MemoryRetriever(repository,properties).retrieve("Vision","p").memories().size());
    var tiny=new MemoryProperties(true,20,80,10,3,2000,60,null,new MemoryProperties.Retrieval(1,10),null);
    assertTrue(new MemoryRetriever(repository,tiny).retrieve("Vision","p").memories().isEmpty());
    var single=new MemoryProperties(true,20,80,10,3,2000,60,null,new MemoryProperties.Retrieval(1,1500),null);
    assertEquals(1,new MemoryRetriever(repository,single).retrieve("Vision","p").memories().size());
  }
  @Test void disabledNeverQueriesRepository() {
    var disabled=new MemoryProperties(false,20,80,10,3,2000,60,null);
    assertEquals("",new MemoryRetriever(null,disabled).retrieve("Vision","p").context());
  }
  @Test void japaneseSummaryAndTagsAreSearchableAndPunctuationIsSafe() {
    repository.insert(new MemoryCandidate(MemoryType.FACT,MemoryScope.GLOBAL,"安定した事実","日本語の要約",.9,.9,
        java.util.List.of("t"),java.util.List.of("音声入力")),null,"s");
    assertEquals(1,repository.search("音声入力","p",10).size());
    assertEquals(1,repository.search("音声入力について覚えていることは？","p",10).size());
    assertEquals(1,repository.search("日本語","p",10).size());
    assertEquals(1,repository.search("要約","p",10).size());
    assertTrue(repository.search("\" OR * - ()","p",10).isEmpty());
  }
}
