package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 活動中実行作業状況の結果と状態の契約を検証するテスト。 */
class ActiveRunActivityTest {
  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void oneCompletionDoesNotMakeOtherRunsIdle() {
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c, p, q) -> {});
    var tracker = new DefaultAgentActivityTracker(Clock.systemUTC());
    var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(router);
    tracker.setActiveRuns(provider);
    router.submit(Path.of("a"), "project:" + UUID.randomUUID() + ":chat:main", "A");
    router.submit(Path.of("b"), "project:" + UUID.randomUUID() + ":chat:main", "B");
    tracker.recordAgentStarted(Instant.now());
    tasks.getFirst().run();
    tracker.recordAgentCompleted(Instant.now());
    assertThat(tracker.isAgentBusy()).isTrue();
    tasks.get(1).run();
    assertThat(tracker.isAgentBusy()).isFalse();
  }
}
