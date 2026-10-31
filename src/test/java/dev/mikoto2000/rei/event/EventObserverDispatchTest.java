package dev.mikoto2000.rei.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EventObserverDispatchTest {
  private AgentEvent event(String id) {
    return event(id, "run");
  }

  private AgentEvent event(String id, String run) {
    return new AgentEvent(
        id,
        0,
        Instant.now(),
        AgentEventType.MESSAGE_DELTA,
        1,
        "session",
        null,
        run,
        null,
        null,
        new MessageDeltaPayload("message", id));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError(error);
    }
  }

  @Test
  void blockedDisplayDoesNotBlockOtherRunDurabilityOrReplayRegistration() throws Exception {
    var bus = new InMemoryAgentEventBus();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var durable = new CopyOnWriteArrayList<String>();
    var critical = new CopyOnWriteArrayList<String>();
    var displayed = new CopyOnWriteArrayList<String>();
    var live = new CopyOnWriteArrayList<String>();
    bus.subscribePersistence(e -> durable.add(e.id()));
    bus.subscribe(e -> critical.add(e.id()));
    bus.subscribeObserver(
        e -> {
          displayed.add(e.id());
          if (e.id().equals("first")) {
            entered.countDown();
            await(release);
          }
        });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> bus.publish(event("first")));
      await(entered);
      try {
        long started = System.nanoTime();
        executor
            .submit(() -> bus.publishBoundary(event("second", "other-run")))
            .get(3, TimeUnit.SECONDS);
        long elapsed = System.nanoTime() - started;
        assertThat(durable).containsExactly("first", "second");
        assertThat(critical).containsExactly("first", "second");
        assertThat(displayed).containsExactly("first");
        var subscription =
            executor
                .submit(
                    () ->
                        bus.withReplaySnapshot(
                            () -> bus.subscribeObserver("run", 0, e -> live.add(e.id()))))
                .get(3, TimeUnit.SECONDS);
        assertThat(subscription.replay()).extracting(AgentEvent::id).containsExactly("first");
        bus.publish(event("third"));
        release.countDown();
        first.get(3, TimeUnit.SECONDS);
        assertThat(displayed).containsExactly("first", "second", "third");
        assertThat(live).containsExactly("third");
        subscription.subscription().unsubscribe();
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/observer-benchmark"));
        java.nio.file.Files.writeString(
            java.nio.file.Path.of("target/observer-benchmark/blocked-display.json"),
            "{\"otherPublishNanos\":" + elapsed + ",\"displayWasBlocked\":true}");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void explicitlySelectedShutdownCannotOvertakeEarlierQueuedRunFrames() throws Exception {
    var bus = new InMemoryAgentEventBus();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var shown = new CopyOnWriteArrayList<String>();
    bus.subscribeObserver(
        e -> {
          if (e.id().equals("first")) {
            entered.countDown();
            await(release);
          }
        });
    var subscription =
        bus.subscribeObserver(
            "run",
            0,
            e -> shown.add(e.id()),
            e -> e.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> bus.publish(event("first")));
      await(entered);
      try {
        bus.publish(event("second"));
        var stop =
            new AgentEventFactory(java.time.Clock.systemUTC()).applicationShutdownStarted("test");
        bus.publish(stop);
        assertThat(shown).isEmpty();
        release.countDown();
        first.get(3, TimeUnit.SECONDS);
        assertThat(shown).containsExactly("first", "second", stop.id());
      } finally {
        release.countDown();
      }
    } finally {
      subscription.subscription().unsubscribe();
    }
  }

  @Test
  void fullObserverQueueAppliesBackpressureWithoutHoldingStateMonitor() throws Exception {
    var bus = new InMemoryAgentEventBus(2);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempting = new CountDownLatch(1);
    var shown = new CopyOnWriteArrayList<String>();
    bus.subscribeObserver(
        e -> {
          shown.add(e.id());
          if (e.id().equals("first")) {
            entered.countDown();
            await(release);
          }
        });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> bus.publish(event("first")));
      await(entered);
      try {
        bus.publish(event("second"));
        bus.publish(event("third"));
        var fourth =
            executor.submit(
                () -> {
                  attempting.countDown();
                  bus.publish(event("fourth"));
                });
        await(attempting);
        assertThatThrownBy(() -> fourth.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(
                executor
                    .submit(() -> bus.withReplaySnapshot(bus::lastSequence))
                    .get(3, TimeUnit.SECONDS))
            .isEqualTo(3);
        release.countDown();
        first.get(3, TimeUnit.SECONDS);
        fourth.get(3, TimeUnit.SECONDS);
        assertThat(shown).containsExactly("first", "second", "third", "fourth");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void observerReentryRejectsOverflowBeforePersistenceAndRecovers() {
    var bus = new InMemoryAgentEventBus(1);
    var durable = new CopyOnWriteArrayList<String>();
    var shown = new CopyOnWriteArrayList<String>();
    bus.subscribePersistence(e -> durable.add(e.id()));
    bus.subscribeObserver(
        e -> {
          shown.add(e.id());
          if (e.id().equals("first")) {
            bus.publish(event("second"));
            assertThatThrownBy(() -> bus.publish(event("overflow")))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
          }
        });
    bus.publish(event("first"));
    bus.publish(event("last"));
    assertThat(durable).containsExactly("first", "second", "last");
    assertThat(shown).containsExactlyElementsOf(durable);
    assertThat(bus.lastSequence()).isEqualTo(3);
  }

  @Test
  void failuresAndUnsubscribeDoNotLeakReservedCapacityOrAffectCriticalListeners() {
    var bus = new InMemoryAgentEventBus(2);
    var shown = new CopyOnWriteArrayList<String>();
    var failed =
        bus.subscribePersistence(
            e -> {
              throw new IllegalStateException("disk failed");
            });
    bus.subscribeObserver(
        e -> {
          throw new IllegalStateException("display failed");
        });
    var subscription = bus.subscribeObserver(e -> shown.add(e.id()));
    assertThatThrownBy(() -> bus.publish(event("bad"))).isInstanceOf(IllegalStateException.class);
    failed.unsubscribe();
    bus.publish(event("good"));
    subscription.unsubscribe();
    bus.publish(event("later"));
    assertThat(shown).containsExactly("good");
  }
}
