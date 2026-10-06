package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.service.*;

@Tag("integration")
class SleepPersistentBudgetTest {
  @TempDir Path dir;
  MemoryRepository repository() {
    var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"));
    var properties=new MemoryProperties(true,20,80,10,3,2000,60,null);
    return new MemoryRepository(source,new MemoryService(source,properties));
  }
  @Test void reservationSurvivesRestartAndCannotBeRefundedByFailure() {
    repository().reserveSleepModelCall("p",2,0);
    repository().reserveSleepModelCall("p",2,0);
    assertTrue(assertThrows(RuntimeException.class,()->repository().reserveSleepModelCall("p",2,0))
        .getMessage().contains("LLM_CALL_BUDGET_EXCEEDED"));
    assertDoesNotThrow(()->repository().reserveSleepModelCall("other",2,0));
  }
  @Test void tokenUsageAccumulatesAndExactBoundaryPreventsNextCall() {
    var repository=repository();
    repository.reserveSleepModelCall("p",0,5);repository.recordSleepTokens("p",2);
    repository().reserveSleepModelCall("p",0,5);repository().recordSleepTokens("p",3);
    assertTrue(assertThrows(RuntimeException.class,()->repository().reserveSleepModelCall("p",0,5))
        .getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
  }
  @Test void unreportedInFlightAndUnknownUsageFailClosedAcrossRestart() {
    repository().reserveSleepModelCall("pending",0,5);
    assertTrue(assertThrows(RuntimeException.class,()->repository().reserveSleepModelCall("pending",0,5))
        .getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    repository().reserveSleepModelCall("unknown",0,5);repository().recordSleepTokens("unknown",null);
    assertTrue(assertThrows(RuntimeException.class,()->repository().reserveSleepModelCall("unknown",0,5))
        .getMessage().contains("TOKEN_USAGE_UNKNOWN"));
  }
  @Test void overshootIsChargedBeforeStoppingAndRaisingCapDoesNotEraseUsage() {
    repository().reserveSleepModelCall("p",0,3);repository().recordSleepTokens("p",4);
    assertThrows(RuntimeException.class,()->repository().reserveSleepModelCall("p",0,3));
    repository().reserveSleepModelCall("p",0,5);repository().recordSleepTokens("p",1);
    assertThrows(RuntimeException.class,()->repository().reserveSleepModelCall("p",0,5));
  }
  @Test void concurrentReservationsCannotExceedProjectCallLimit() throws Exception {
    var repository=repository();
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks=new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
      for(int i=0;i<8;i++)tasks.add(()->{
        try {repository.reserveSleepModelCall("p",3,0);return true;}
        catch(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped) {return false;}
      });
      int accepted=0;
      for(var result:executor.invokeAll(tasks))if(result.get())accepted++;
      assertEquals(3,accepted);
    }
  }
}
