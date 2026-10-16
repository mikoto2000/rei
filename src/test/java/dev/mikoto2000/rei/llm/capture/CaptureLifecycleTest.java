package dev.mikoto2000.rei.llm.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.ErrorInformation;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CaptureLifecycleTest {
  @Test
  void cancellationEncodedByExistingChatRunFailureIsAcancelledSession() {
    var store = new CaptureStore();
    store.reserve("c", "v");
    store.accept("c", "v", "s", "r");
    var listener = new AtomicReference<AgentEventListener>();
    var bus = mock(AgentEventBus.class);
    when(bus.subscribe(any()))
        .thenAnswer(
            invocation -> {
              listener.set(invocation.getArgument(0));
              return (AgentEventBus.Subscription) () -> {};
            });
    try (var lifecycle = new CaptureLifecycle(store, bus)) {
      listener
          .get()
          .onEvent(
              new AgentEventFactory(Clock.systemUTC())
                  .runFailed("r", new ErrorInformation("Cancellation", "cancelled", "cancelled")));
      assertThat(store.sessions().getFirst().outcome()).isEqualTo("CANCELLED");
      assertThat(store.sessions().getFirst().ended()).isNotNull();
    }
  }
}
