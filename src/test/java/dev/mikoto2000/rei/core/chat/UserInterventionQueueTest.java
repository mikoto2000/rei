package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 利用者追加入力実行待ちの結果と状態の契約を検証するテスト。 */
class UserInterventionQueueTest {
  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void mailboxIsBoundedAndCapacityReturnsAfterDrain() {
    var queue = new UserInterventionQueue();
    for (int i = 0; i < 32; i++) assertThat(queue.offer("guidance " + i)).isTrue();
    assertThat(queue.offer("overflow")).isFalse();
    assertThat(queue.drain()).hasSize(32);
    assertThat(queue.offer("next")).isTrue();
    assertThatThrownBy(() -> queue.offer("x".repeat(16385)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queue.offer("  ")).isInstanceOf(IllegalArgumentException.class);
  }

  /** 会話の実行で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void appliesInOrderOnlyAtSafePoint() {
    var queue = new UserInterventionQueue();
    assertThat(queue.offer("first")).isTrue();
    assertThat(queue.offer("second")).isTrue();
    assertThat(queue.finishIfEmpty()).isFalse();
    assertThat(queue.drain()).containsExactly("first", "second");
    assertThat(queue.finishIfEmpty()).isTrue();
    assertThat(queue.offer("late")).isFalse();
  }

  /** 会話の実行で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completionRacingInputEitherAcceptsOrHandsBackInput() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 100; i++) {
        var queue = new UserInterventionQueue();
        var barrier = new CyclicBarrier(2);
        var input =
            executor.submit(
                () -> {
                  barrier.await();
                  return queue.offer("guidance");
                });
        var completion =
            executor.submit(
                () -> {
                  barrier.await();
                  return queue.finishIfEmpty();
                });
        boolean accepted = input.get(5, TimeUnit.SECONDS);
        assertThat(completion.get(5, TimeUnit.SECONDS)).isEqualTo(!accepted);
        assertThat(queue.drain()).hasSize(accepted ? 1 : 0);
      }
    }
  }
}
