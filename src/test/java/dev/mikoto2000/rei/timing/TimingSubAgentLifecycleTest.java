package dev.mikoto2000.rei.timing;
import java.time.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.junit.jupiter.api.Assertions.*;
class TimingSubAgentLifecycleTest {
 @Test void independentChildRunUsesExistingLifecycleWithoutKeepingTaskText() {
  var store=new TimingStore(true,10,20,100,Duration.ofHours(1),Clock.systemUTC(),System::nanoTime);
  var bus=new InMemoryAgentEventBus();var factory=new AgentEventFactory(Clock.systemUTC());
  var child=new AgentRunContext("child","subagent:child",Path.of("."),"project");
  try(var observer=new TimingEventObserver(store,bus)) {
   bus.publish(factory.subAgentLifecycle(AgentEventType.SUBAGENT_STARTED,"parent","child","worker","TASK-SECRET","RUNNING",0,null).withOwnership(child));
   assertTrue(store.snapshot("project","subagent:child","child").isPresent());
   assertTrue(store.beginSpan("child","call",null,null,null,TimingRecorder.Category.LLM));store.endSpan("child","call",TimingRecorder.Status.SUCCESS);
   bus.publish(factory.subAgentLifecycle(AgentEventType.SUBAGENT_FAILED,"parent","child","worker","TASK-SECRET","CANCELLED",1,null).withOwnership(child));
   var run=store.snapshot("project","subagent:child","child").orElseThrow();assertEquals(TimingRecorder.Status.CANCELLED,run.status());assertFalse(run.toString().contains("SECRET"));
   assertTrue(store.snapshot("project","wrong-session","child").isEmpty());
  }
 }
}
