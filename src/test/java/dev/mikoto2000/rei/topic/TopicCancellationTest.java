package dev.mikoto2000.rei.topic;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.AgentEventFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TopicCancellationTest {
  @Test void cancelledRefreshDoesNotContinueWithOtherGenerators() {
    var first = mock(TopicCandidateGenerator.class);
    var next = mock(TopicCandidateGenerator.class);
    when(first.generate(any())).thenThrow(new RuntimeException(new InterruptedException("cancelled")));
    var properties = new TopicGeneratorProperties(); properties.setEnabled(true);
    var service = new TopicGeneratorService(List.of(first, next), null, null, null, null,
        properties, new AgentEventFactory(Clock.systemUTC()), event -> {}, Clock.systemUTC());
    try {
      assertThatThrownBy(() -> service.prepareCandidates(null)).isInstanceOf(java.util.concurrent.CancellationException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verifyNoInteractions(next);
    } finally { Thread.interrupted(); }
  }
}
