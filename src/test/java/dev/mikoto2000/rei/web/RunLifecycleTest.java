package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.run.RunNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 実行受付から終了までの管理の結果と状態の契約を検証するテスト。 */
class RunLifecycleTest {
  /** Web APIによる操作で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void rejectedQueuedSuccessorIsFailedInsteadOfRemainingQueuedForever() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    List<Runnable> tasks = new ArrayList<>();
    var submissions = new java.util.concurrent.atomic.AtomicInteger();
    var router =
        new dev.mikoto2000.rei.core.chat.ConversationInputRouter(
            work -> {
              if (submissions.incrementAndGet() > 1)
                throw new java.util.concurrent.RejectedExecutionException("executor closing");
              tasks.add(work);
            },
            (context, prompt, input) -> bus.publish(factory.runCompleted(context.runId(), 1)));
    try (var service =
        new RunService(
            registry, bus, factory, new CommandCancellationService(), router::cancelQueued)) {
      for (String id : List.of("one", "two")) {
        var context = RunRegistryTest.context(id);
        registry.register(context);
        router.submit(context, id, work -> service.execute(context, work));
      }
      assertThatThrownBy(() -> tasks.removeFirst().run())
          .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
      assertThat(service.get("one").status()).isEqualTo(RunStatus.COMPLETED);
      assertThat(service.get("two").status()).isEqualTo(RunStatus.FAILED);
      assertThat(router.activeRuns()).isEmpty();
    }
  }

  /** Web APIによる操作で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void executionUpdatesRegistryAndRecoversMissingTerminalEvents() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var service =
        new RunService(registry, bus, factory, new CommandCancellationService(), id -> false);
    for (String id : List.of("completed", "silent", "throws"))
      registry.register(RunRegistryTest.context(id));
    service.execute(
        RunRegistryTest.context("completed"),
        () -> {
          assertThat(service.get("completed").status()).isEqualTo(RunStatus.RUNNING);
          bus.publish(factory.runCompleted("completed", 1));
        });
    service.execute(RunRegistryTest.context("silent"), () -> {});
    service.execute(
        RunRegistryTest.context("throws"),
        () -> {
          throw new IllegalStateException("failed");
        });
    assertThat(service.get("completed").status()).isEqualTo(RunStatus.COMPLETED);
    assertThat(service.get("silent").status()).isEqualTo(RunStatus.FAILED);
    assertThat(service.get("throws").status()).isEqualTo(RunStatus.FAILED);
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void queuedCancelIsIdempotentPublishesOnceAndNeverStartsRunner() {
    var registry = new RunRegistry(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    List<String> removed = new ArrayList<>();
    var service =
        new RunService(
            registry,
            bus,
            new AgentEventFactory(Clock.systemUTC()),
            new CommandCancellationService(),
            id -> removed.add(id));
    registry.register(RunRegistryTest.context("run"));
    assertThat(service.cancel("run").accepted()).isTrue();
    assertThat(service.cancel("run").accepted()).isFalse();
    service.execute(
        RunRegistryTest.context("run"),
        () -> {
          throw new AssertionError("cancelled runner started");
        });
    assertThat(events)
        .extracting(AgentEvent::type)
        .containsExactly(AgentEventType.AGENT_RUN_CANCELLED);
    assertThat(removed).containsExactly("run");
    assertThat(service.get("run").status()).isEqualTo(RunStatus.CANCELLED);
    assertThatThrownBy(() -> service.cancel("unknown")).isInstanceOf(RunNotFoundException.class);
  }
}
