package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveRunActivityTest {
  @Test void oneCompletionDoesNotMakeOtherRunsIdle() {
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c,p,q) -> {});
    var tracker = new DefaultAgentActivityTracker(Clock.systemUTC());
    var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(router);
    tracker.setActiveRuns(provider);
    router.submit(Path.of("a"), "project:" + UUID.randomUUID() + ":chat:main", "A");
    router.submit(Path.of("b"), "project:" + UUID.randomUUID() + ":chat:main", "B");
    tracker.recordAgentStarted(Instant.now());
    tasks.getFirst().run(); tracker.recordAgentCompleted(Instant.now());
    assertThat(tracker.isAgentBusy()).isTrue();
    tasks.get(1).run();
    assertThat(tracker.isAgentBusy()).isFalse();
  }
}
