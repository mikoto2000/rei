package dev.mikoto2000.rei;

import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class ApplicationShutdownNotifierTest {
  @Test void contextCloseAnnouncesBeforeLifecycleStop() {
    var bus = new InMemoryAgentEventBus();
    var order = new ArrayList<String>();
    bus.subscribe(event -> {
      assertThat(event.type()).isEqualTo(AgentEventType.APPLICATION_SHUTDOWN_STARTED);
      assertThat(event.runId()).isNull();
      assertThat(event.projectId()).isNull();
      assertThat(event.payload()).isEqualTo(new ApplicationShutdownStartedPayload("context_closed"));
      order.add("event");
    });
    var context = new GenericApplicationContext();
    context.registerBean(ApplicationShutdownNotifier.class,
        () -> new ApplicationShutdownNotifier(bus, new AgentEventFactory(Clock.systemUTC()), context));
    context.registerBean(SmartLifecycle.class, () -> new SmartLifecycle() {
      private boolean running;
      public void start() { running = true; }
      public void stop() { order.add("stop"); running = false; }
      public boolean isRunning() { return running; }
    });
    context.refresh();
    context.close();
    assertThat(order).containsExactly("event", "stop");
  }

  @Test void shellExitAndContextClosePublishOnlyOnce() {
    var bus = new InMemoryAgentEventBus();
    var received = new ArrayList<AgentEvent>();
    bus.subscribe(received::add);
    try (var context = new GenericApplicationContext()) {
      var notifier = new ApplicationShutdownNotifier(bus, new AgentEventFactory(Clock.systemUTC()), context);
      context.addApplicationListener(notifier);
      context.refresh();
      notifier.begin("shell_exit");
      context.close();
    }
    assertThat(received).hasSize(1);
    assertThat(received.getFirst().payload()).isEqualTo(new ApplicationShutdownStartedPayload("shell_exit"));
  }
}
