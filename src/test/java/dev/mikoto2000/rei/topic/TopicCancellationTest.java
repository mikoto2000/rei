package dev.mikoto2000.rei.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.event.AgentEventFactory;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 話題キャンセルの結果と状態の契約を検証するテスト。 */
class TopicCancellationTest {
  /** 話題の管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledRefreshDoesNotContinueWithOtherGenerators() {
    var first = mock(TopicCandidateGenerator.class);
    var next = mock(TopicCandidateGenerator.class);
    when(first.generate(any()))
        .thenThrow(new RuntimeException(new InterruptedException("cancelled")));
    var properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    var service =
        new TopicGeneratorService(
            List.of(first, next),
            null,
            null,
            null,
            null,
            properties,
            new AgentEventFactory(Clock.systemUTC()),
            event -> {},
            Clock.systemUTC());
    try {
      assertThatThrownBy(() -> service.prepareCandidates(null))
          .isInstanceOf(java.util.concurrent.CancellationException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(next);
    } finally {
      Thread.interrupted();
    }
  }
}
