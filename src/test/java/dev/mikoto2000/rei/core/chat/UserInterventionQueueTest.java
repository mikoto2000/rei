package dev.mikoto2000.rei.core.chat;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class UserInterventionQueueTest {
  @Test void appliesInOrderOnlyAtSafePoint() {
    var queue = new UserInterventionQueue();
    assertThat(queue.offer("first")).isTrue();
    assertThat(queue.offer("second")).isTrue();
    assertThat(queue.finishIfEmpty()).isFalse();
    assertThat(queue.drain()).containsExactly("first", "second");
    assertThat(queue.finishIfEmpty()).isTrue();
    assertThat(queue.offer("late")).isFalse();
  }

  @Test void completionRacingInputEitherAcceptsOrHandsBackInput() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++) {
        var queue = new UserInterventionQueue();
        var barrier = new CyclicBarrier(2);
        var input = executor.submit(() -> { barrier.await(); return queue.offer("guidance"); });
        var completion = executor.submit(() -> { barrier.await(); return queue.finishIfEmpty(); });
        boolean accepted = input.get(5, TimeUnit.SECONDS);
        assertThat(completion.get(5, TimeUnit.SECONDS)).isEqualTo(!accepted);
        assertThat(queue.drain()).hasSize(accepted ? 1 : 0);
      }
    }
  }
}
