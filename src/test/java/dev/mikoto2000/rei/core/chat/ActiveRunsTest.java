package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ActiveRunsTest {
  String conversation(String id) { return "project:" + id + ":chat:main"; }

  @Test void registersImmediatelyAndSameProjectInputRemainsAnIntervention() {
    var tasks = new ArrayList<Runnable>();
    var id = UUID.randomUUID().toString();
    var router = new ConversationInputRouter(tasks::add, (context, prompt, queue) ->
        assertThat(queue.drain()).containsExactly("guidance"));
    router.submit(Path.of("a"), conversation(id), "first\n" + "x".repeat(200));
    assertThat(router.activeRuns()).hasSize(1);
    var run = router.activeRuns().getFirst();
    assertThat(run.projectId()).isEqualTo(id);
    assertThat(run.conversationId()).isEqualTo(conversation(id));
    assertThat(run.requestSummary()).hasSizeLessThanOrEqualTo(80).doesNotContain("\n");
    assertThat(run.startedAt()).isNotNull();
    assertThat(router.submit(Path.of("relocated"), conversation(id), "guidance"))
        .isEqualTo(ConversationInputRouter.Disposition.QUEUED);
    assertThat(router.activeRuns()).containsExactly(run);
    tasks.getFirst().run();
    assertThat(router.activeRuns()).isEmpty();
  }

  @Test void independentProjectsActuallyRunConcurrentlyUsingProductionExecutor() throws Exception {
    var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
    try (var executor = new AgentRunConfiguration().agentRunExecutor()) {
      var router = new ConversationInputRouter(executor, (context, prompt, queue) -> {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      });
      try {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        router.submit(Path.of("a"), conversation(a), "A");
        router.submit(Path.of("b"), conversation(b), "B");
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(router.activeRuns()).extracting(ActiveRun::projectId).containsExactlyInAnyOrder(a, b);
      } finally { release.countDown(); }
    }
  }

  @Test void everyTerminalExitRemovesRunAndListenerFailureCannotLeaveGhosts() {
    for (RuntimeException failure : List.of(new RuntimeException("failure"), new CancellationException(),
        new CompletionException(new TimeoutException()))) {
      var tasks = new ArrayList<Runnable>();
      var router = new ConversationInputRouter(tasks::add, (context, prompt, queue) -> { throw failure; });
      router.onChange(() -> { throw new IllegalStateException("UI failed"); });
      router.submit(Path.of("a"), conversation(UUID.randomUUID().toString()), "start");
      assertThatThrownBy(() -> tasks.getFirst().run()).isSameAs(failure);
      assertThat(router.activeRuns()).isEmpty();
    }
    var router = new ConversationInputRouter(task -> { throw new RejectedExecutionException(); }, (c,p,q) -> {});
    assertThatThrownBy(() -> router.submit(Path.of("a"), "chat:main", "start")).isInstanceOf(RejectedExecutionException.class);
    assertThat(router.activeRuns()).isEmpty();
  }

  @Test void closedMailboxSuccessorWaitsUntilPreviousRunnerExits() {
    var tasks = new ArrayList<Runnable>();
    var ref = new ConversationInputRouter[1];
    ref[0] = new ConversationInputRouter(tasks::add, (context, prompt, queue) -> {
      queue.finishIfEmpty();
      if (prompt.equals("first")) {
        ref[0].submit(Path.of("a"), "chat:main", "next");
        assertThat(tasks).hasSize(1);
        assertThat(ref[0].activeRuns().getFirst().runId()).isEqualTo(context.runId());
      }
    });
    ref[0].submit(Path.of("a"), "chat:main", "first");
    tasks.getFirst().run();
    assertThat(tasks).hasSize(2);
    tasks.get(1).run();
    assertThat(ref[0].activeRuns()).isEmpty();
  }

  @Test void concurrentStartsAndCompletionsNeverLoseEntries() throws Exception {
    var tasks = new ConcurrentLinkedQueue<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c,p,q) -> {});
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 50; i++) futures.add(executor.submit(() ->
          router.submit(Path.of("root"), conversation(UUID.randomUUID().toString()), "work")));
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
      assertThat(router.activeRuns()).hasSize(50);
      futures.clear();
      for (var task : tasks) futures.add(executor.submit(task));
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
      assertThat(router.activeRuns()).isEmpty();
      assertThat(new ConversationInputRouter(tasks::add, (c,p,q) -> {}).activeRuns()).isEmpty();
    }
  }
}
