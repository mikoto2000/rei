package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConversationInputRouterTest {
  @Test void explicitGuidanceIsDeliveredOnlyToTheNamedRunAndOwner() {
    var scheduled = new ArrayList<Runnable>();
    var inputs = new LinkedHashMap<String,List<String>>();
    var router = new ConversationInputRouter(scheduled::add, (owner,prompt,queue) -> inputs.put(owner.runId(),queue.drain()));
    var first = new AgentRunContext("first","session",Path.of("."),"A");
    var second = new AgentRunContext("second","session",Path.of("."),"A",AgentRunContext.RequestSource.WEB,AgentRunContext.Mode.CONVERSATION);
    router.submit(first,"task");router.submit(second,"question");
    assertThat(router.offerIntervention("A","session","first","guidance")).isTrue();
    assertThat(router.offerIntervention("B","session","first","wrong project")).isFalse();
    assertThat(router.offerIntervention("A","other","first","wrong session")).isFalse();
    assertThat(scheduled).hasSize(2);
    scheduled.forEach(Runnable::run);
    assertThat(inputs.get("first")).containsExactly("guidance");assertThat(inputs.get("second")).isEmpty();
    assertThat(router.offerIntervention("A","session","first","late")).isFalse();
  }
  @Test void persistentProjectIdentityOwnsMailboxEvenIfLocationChanges() {
    List<Runnable> tasks = new ArrayList<>();
    String conversation = "project:" + UUID.randomUUID() + ":chat:main";
    var router = new ConversationInputRouter(tasks::add, (context, prompt, queue) -> {});
    router.submit(Path.of("old-location"), conversation, "start");
    assertThat(router.submit(Path.of("new-location"), conversation, "guidance"))
        .isEqualTo(ConversationInputRouter.Disposition.QUEUED);
    assertThat(tasks).hasSize(1);
  }
  @Test void idleStartsAndRunningInputQueuesWithoutAnotherTask() {
    List<Runnable> tasks = new ArrayList<>();
    List<String> prompts = new ArrayList<>();
    var router = new ConversationInputRouter(tasks::add,
        (context, prompt, queue) -> { prompts.add(prompt); assertThat(queue.drain()).containsExactly("guidance"); });
    var root = Path.of(".");
    assertThat(router.submit(root, "chat:main", "start")).isEqualTo(ConversationInputRouter.Disposition.STARTED);
    assertThat(router.submit(root, "chat:main", "guidance")).isEqualTo(ConversationInputRouter.Disposition.QUEUED);
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly("start");
    assertThat(router.submit(root, "chat:main", "next")).isEqualTo(ConversationInputRouter.Disposition.STARTED);
  }

  @Test void inputAfterMailboxClosesStartsSuccessorWithoutLoss() {
    List<Runnable> tasks = new ArrayList<>();
    List<String> prompts = new ArrayList<>();
    ConversationInputRouter[] ref = new ConversationInputRouter[1];
    var root = Path.of(".");
    ref[0] = new ConversationInputRouter(tasks::add, (context, prompt, queue) -> {
      prompts.add(prompt);
      assertThat(queue.finishIfEmpty()).isTrue();
      if (prompt.equals("start")) ref[0].submit(root, "chat:main", "late");
    });
    ref[0].submit(root, "chat:main", "start");
    tasks.removeFirst().run();
    tasks.removeFirst().run();
    assertThat(prompts).containsExactly("start", "late");
  }
}
