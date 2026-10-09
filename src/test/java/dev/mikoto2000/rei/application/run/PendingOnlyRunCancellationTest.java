package dev.mikoto2000.rei.application.run;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;

class PendingOnlyRunCancellationTest {
  @Test void alreadyDispatchedWorkIsNotCancelledByPendingOnlyRequest() {
    var registry=new RunRegistry(Clock.systemUTC());var bus=new InMemoryAgentEventBus();
    var owner=new AgentRunContext("run","session",Path.of("."),"p");registry.register(owner);
    try(var runs=new RunService(registry,bus,new AgentEventFactory(Clock.systemUTC()),
        new CommandCancellationService(),id->false)){
      assertThat(runs.cancelQueuedOnly("run").accepted()).isFalse();
      assertThat(runs.get("run").status()).isEqualTo(RunStatus.QUEUED);
      registry.transition("run",RunStatus.RUNNING,null);
      assertThat(runs.cancelQueuedOnly("run").accepted()).isFalse();
      assertThat(runs.get("run").status()).isEqualTo(RunStatus.RUNNING);
    }
  }
  @Test void queuedOnlyCancellationPublishesTerminalAndNotifiesOnceOutsideBusLock() {
    var registry=new RunRegistry(Clock.systemUTC());var bus=new InMemoryAgentEventBus();
    var owner=new AgentRunContext("run","session",Path.of("."),"p");registry.register(owner);
    var calls=new AtomicInteger();
    try(var runs=new RunService(registry,bus,new AgentEventFactory(Clock.systemUTC()),
        new CommandCancellationService(),id->true)){
      runs.onQueuedCancellation("run",()->{assertThat(Thread.holdsLock(bus)).isFalse();calls.incrementAndGet();});
      assertThat(runs.cancelQueuedOnly("run").accepted()).isTrue();
      assertThat(runs.get("run").status()).isEqualTo(RunStatus.CANCELLED);
      assertThat(runs.cancelQueuedOnly("run").accepted()).isFalse();
      assertThat(calls.get()).isEqualTo(1);
    }
  }
}