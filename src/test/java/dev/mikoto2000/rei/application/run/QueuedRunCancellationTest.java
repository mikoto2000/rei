package dev.mikoto2000.rei.application.run;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
class QueuedRunCancellationTest {
 @Test void terminalCompletionCannotOverwriteAcceptedCancellationOrPublishFalseSuccess() {
  var registry=new RunRegistry(Clock.systemUTC());var bus=new InMemoryAgentEventBus();var owner=new AgentRunContext("run","session",Path.of("."),"p");registry.register(owner);
  var events=new java.util.ArrayList<AgentEvent>();bus.subscribe(events::add);
  try(var runs=new RunService(registry,bus,new AgentEventFactory(Clock.systemUTC()),new CommandCancellationService(),id->true)) {
   runs.cancel("run");runs.finishMissingTerminal(owner,RunStatus.COMPLETED);
   assertEquals(RunStatus.CANCELLED,runs.get("run").status());assertTrue(events.stream().noneMatch(event->event.type()==AgentEventType.AGENT_RUN_COMPLETED));
  }
 }
 @Test void cancellationAfterQueueDispatchButBeforeLifecycleStartsNotifiesWithoutRunningWork() {
  var registry=new RunRegistry(Clock.systemUTC());var bus=new InMemoryAgentEventBus();var calls=new AtomicInteger();var work=new AtomicInteger();
  var owner=new AgentRunContext("run","session",Path.of("."),"p");registry.register(owner);
  try(var runs=new RunService(registry,bus,new AgentEventFactory(Clock.systemUTC()),new CommandCancellationService(),id->false)) {
   runs.onQueuedCancellation("run",()->{assertFalse(Thread.holdsLock(bus));calls.incrementAndGet();});
   runs.cancel("run");runs.execute(owner,work::incrementAndGet);runs.execute(owner,work::incrementAndGet);
   assertEquals(0,work.get());assertEquals(1,calls.get());
  }
 }
 @Test void explicitQueuedCancellationNotifiesOnceAfterTerminalStateIsPublished() {
  var registry=new RunRegistry(Clock.systemUTC());var bus=new InMemoryAgentEventBus();var calls=new AtomicInteger();
  var owner=new AgentRunContext("run","session",Path.of("."),"p");registry.register(owner);
  try(var runs=new RunService(registry,bus,new AgentEventFactory(Clock.systemUTC()),new CommandCancellationService(),id->true)) {
   runs.onQueuedCancellation("run",()->{assertFalse(Thread.holdsLock(bus));assertEquals(RunStatus.CANCELLED,runs.get("run").status());calls.incrementAndGet();});
   assertTrue(runs.cancel("run").accepted());assertFalse(runs.cancel("run").accepted());assertEquals(1,calls.get());
  }
 }
}
