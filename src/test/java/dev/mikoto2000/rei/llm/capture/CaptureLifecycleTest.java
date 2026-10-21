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

/** 取得記録受付から終了までの管理の結果と状態の契約を検証するテスト。 */
class CaptureLifecycleTest {
  /** モデル応答の記録の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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
