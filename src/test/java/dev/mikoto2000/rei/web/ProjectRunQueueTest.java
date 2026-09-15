package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.chat.ProjectRunQueue;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProjectRunQueueTest {
  @Test void cancellationDuringDispatchDiscardsLateScheduledView() throws Exception {
    var scheduling = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var visible = new java.util.concurrent.atomic.AtomicBoolean();
    var queue = new ProjectRunQueue(Runnable::run);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future = executor.submit(() -> queue.enqueue("project", "run", () -> fail("Cancelled work started"),
          () -> {
            scheduling.countDown();
            try { release.await(); } catch (InterruptedException error) { throw new RuntimeException(error); }
            visible.set(true);
          }, () -> visible.set(false), error -> { throw error; }));
      try {
        assertThat(scheduling.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.cancelQueued("run")).isTrue();
      } finally { release.countDown(); }
      future.get(5, TimeUnit.SECONDS);
      assertThat(visible).isFalse();
    }
  }
  @Test void serializesSameProjectAndRemovesQueuedRunById() {
    List<Runnable> tasks = new ArrayList<>();
    List<String> executed = new ArrayList<>();
    var queue = new ProjectRunQueue(tasks::add);
    queue.enqueue("project", "one", () -> executed.add("one"));
    queue.enqueue("project", "two", () -> executed.add("two"));
    queue.enqueue("project", "three", () -> executed.add("three"));
    assertThat(tasks).hasSize(1);
    assertThat(queue.cancelQueued("two")).isTrue();
    tasks.removeFirst().run();
    tasks.removeFirst().run();
    assertThat(executed).containsExactly("one", "three");
    assertThat(queue.cancelQueued("unknown")).isFalse();
  }

  @Test void differentProjectsProceedAndSuccessorWaitsForFinally() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var other = new CountDownLatch(1);
    var successor = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var queue = new ProjectRunQueue(executor);
      queue.enqueue("first", "one", () -> {
        entered.countDown();
        try { release.await(); } catch (InterruptedException error) { throw new RuntimeException(error); }
      });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      queue.enqueue("first", "two", successor::countDown);
      queue.enqueue("other", "three", other::countDown);
      try {
        assertThat(other.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.cancelQueued("one")).isFalse();
        assertThat(successor.getCount()).isEqualTo(1);
      } finally { release.countDown(); }
      assertThat(successor.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
