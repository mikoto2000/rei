package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 入力の振り分けWeb実行の結果と状態の契約を検証するテスト。 */
class RouterWebRunTest {
  /** Web実行一覧について、指定された識別情報を維持することを確認する。 */
  @Test
  void webRunsKeepSuppliedIdentityAndShareFifoWithCliWithoutSharingInterventions() {
    List<Runnable> tasks = new ArrayList<>();
    List<AgentRunContext> executed = new ArrayList<>();
    String project = UUID.randomUUID().toString();
    String conversation = "project:" + project + ":chat:main";
    var router =
        new ConversationInputRouter(
            tasks::add,
            (context, prompt, input) -> {
              executed.add(context);
              assertThat(input.drain()).isEmpty();
            });
    var first =
        new AgentRunContext("web1", "project:" + project + ":chat:one", Path.of("."), project);
    var second =
        new AgentRunContext("web2", "project:" + project + ":chat:two", Path.of("."), project);
    router.submit(first, "first");
    router.submitNewRun(Path.of("."), conversation, "cli");
    router.submit(second, "second");
    assertThat(tasks).hasSize(1);
    while (!tasks.isEmpty()) tasks.removeFirst().run();
    assertThat(executed).hasSize(3);
    assertThat(executed.getFirst()).isSameAs(first);
    assertThat(executed.getLast()).isSameAs(second);
    assertThat(executed.get(1).conversationId()).isEqualTo(conversation);
  }
}
