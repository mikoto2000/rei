package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActiveRunNarrationTest {
  @Test void completedRunIsRemovedBeforeQueuedNarration() {
    var tasks = new ArrayList<Runnable>();
    var executor = mock(ExecutorService.class);
    doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any());
    var execution = mock(ChatExecutionService.class);
    when(execution.execute(any(), anyString(), any())).thenReturn(ChatExecutionResult.success("answer", false));
    var narrator = mock(ChatResponseNarrator.class);
    var router = new AgentRunConfiguration().conversationInputRouter(executor, execution, narrator,
        new AgentEventFactory(Clock.systemUTC()), event -> {});
    router.submit(Path.of("a"), "chat:main", "work");
    var context = router.activeRuns().getFirst();
    doAnswer(call -> {
      assertThat(AgentRunScope.current()).isNotNull();
      assertThat(AgentRunScope.current().runId()).isEqualTo(context.runId());
      return null;
    }).when(narrator).narrateCompletedRun("answer");
    tasks.getFirst().run();
    assertThat(router.activeRuns()).isEmpty();
    verify(narrator, never()).narrateCompletedRun(anyString());
    assertThat(tasks).hasSize(2);
    tasks.get(1).run();
    verify(narrator).narrateCompletedRun("answer");
  }
}
