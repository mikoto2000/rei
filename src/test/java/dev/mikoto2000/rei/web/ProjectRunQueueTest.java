package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.ProjectRunQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** プロジェクト実行実行待ちの結果と状態の契約を検証するテスト。 */
class ProjectRunQueueTest {
  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void readersShareAProjectButWriterWaitsUntilEveryReaderFinishes() {
    var work = new ArrayList<Runnable>();
    var queue = new ProjectRunQueue(work::add);
    queue.enqueue("p", "read-a", ProjectRunQueue.Access.READ_ONLY, () -> {});
    queue.enqueue("p", "read-b", ProjectRunQueue.Access.READ_ONLY, () -> {});
    queue.enqueue("p", "write", () -> {});
    queue.enqueue("p", "late-read", ProjectRunQueue.Access.READ_ONLY, () -> {});
    assertThat(work).hasSize(2);
    work.removeFirst().run();
    assertThat(work).hasSize(1);
    work.removeFirst().run();
    assertThat(work).hasSize(1);
    assertThat(queue.containsRun("p", "write")).isTrue();
    work.removeFirst().run();
    assertThat(work).hasSize(1);
    work.removeFirst().run();
    assertThat(queue.containsRun("p", "late-read")).isFalse();
  }

  /** Web APIによる操作で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void toolFreeConversationCanProceedWhileAnExclusiveRunOwnsTheProject() {
    var work = new ArrayList<Runnable>();
    var results = new ArrayList<String>();
    var queue = new ProjectRunQueue(work::add);
    queue.enqueue("p", "task", () -> results.add("task"));
    queue.enqueue("p", "next-task", () -> results.add("next-task"));
    queue.enqueue(
        "p", "question", ProjectRunQueue.Access.CONVERSATION, () -> results.add("question"));
    assertThat(work).hasSize(2);
    work.removeLast().run();
    assertThat(results).containsExactly("question");
    assertThat(queue.containsRun("p", "task")).isTrue();
    work.removeFirst().run();
    work.removeFirst().run();
    assertThat(results).containsExactly("question", "task", "next-task");
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void parallelDispatchRemainsBoundedAndCancelledReaderNeverExecutes() {
    var work = new ArrayList<Runnable>();
    var queue = new ProjectRunQueue(work::add, 8, 8, 2);
    queue.enqueue("p", "a", ProjectRunQueue.Access.READ_ONLY, () -> {});
    queue.enqueue("p", "b", ProjectRunQueue.Access.READ_ONLY, () -> fail("Cancelled reader ran"));
    queue.enqueue("p", "c", ProjectRunQueue.Access.READ_ONLY, () -> {});
    assertThat(work).hasSize(2);
    assertThat(queue.cancelQueued("b")).isTrue();
    assertThat(work).hasSize(3);
    work.remove(1).run();
    assertThat(queue.containsRun("p", "b")).isFalse();
    work.removeFirst().run();
    work.removeFirst().run();
    assertThat(queue.containsRun("p", "c")).isFalse();
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void boundedAdmissionCountsExecutingAndQueuedJobsAndReleasesCancelledSlots() {
    var tasks = new ArrayList<Runnable>();
    var queue = new ProjectRunQueue(tasks::add, 2, 3);
    queue.enqueue("first", "one", () -> {});
    queue.enqueue("first", "two", () -> {});
    assertThatThrownBy(() -> queue.enqueue("first", "overflow", () -> fail("Rejected work ran")))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    assertThat(queue.containsRun("first", "overflow")).isFalse();
    queue.enqueue("other", "three", () -> {});
    assertThatThrownBy(() -> queue.enqueue("third", "global-overflow", () -> {}))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    assertThat(queue.cancelQueued("two")).isTrue();
    queue.enqueue("third", "four", () -> {});
    assertThat(tasks).hasSize(3);
    tasks.removeFirst().run();
    queue.enqueue("first", "five", () -> {});
    assertThat(queue.containsRun("first", "five")).isTrue();
  }

  /** Web APIによる操作で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void admissionLimitsMustBePositiveAndProjectLimitCannotExceedTotal() {
    assertThatThrownBy(() -> new ProjectRunQueue(Runnable::run, 0, 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProjectRunQueue(Runnable::run, 2, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProjectRunQueue(Runnable::run, 4, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Web APIによる操作で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void executorRejectionDoesNotKeepAnAdmissionSlot() {
    var reject = new java.util.concurrent.atomic.AtomicBoolean(true);
    var queue =
        new ProjectRunQueue(
            task -> {
              if (reject.get())
                throw new java.util.concurrent.RejectedExecutionException("executor closed");
              task.run();
            },
            1,
            1);
    assertThatThrownBy(() -> queue.enqueue("project", "one", () -> {}))
        .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    assertThat(queue.containsRun("project", "one")).isFalse();
    reject.set(false);
    var executed = new java.util.concurrent.atomic.AtomicBoolean();
    queue.enqueue("project", "two", () -> executed.set(true));
    assertThat(executed).isTrue();
  }

  /** Web APIによる操作で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void concurrentAdmissionsNeverExceedGlobalCapacity() throws Exception {
    var accepted = new java.util.concurrent.atomic.AtomicInteger();
    var dispatched = new java.util.concurrent.atomic.AtomicInteger();
    var queue = new ProjectRunQueue(task -> dispatched.incrementAndGet(), 2, 3);
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var ready = new CountDownLatch(1);
      var futures = new ArrayList<Future<?>>();
      for (int i = 0; i < 32; i++) {
        int index = i;
        futures.add(
            workers.submit(
                () -> {
                  ready.await();
                  try {
                    queue.enqueue("project-" + index, "run-" + index, () -> {});
                    accepted.incrementAndGet();
                  } catch (ProjectRunQueue.CapacityExceededException expected) {
                  }
                  return null;
                }));
      }
      ready.countDown();
      for (var future : futures) future.get(5, TimeUnit.SECONDS);
    }
    assertThat(accepted).hasValue(3);
    assertThat(dispatched).hasValue(3);
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationDuringDispatchDiscardsLateScheduledView() throws Exception {
    var scheduling = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var visible = new java.util.concurrent.atomic.AtomicBoolean();
    var queue = new ProjectRunQueue(Runnable::run);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var future =
          executor.submit(
              () ->
                  queue.enqueue(
                      "project",
                      "run",
                      () -> fail("Cancelled work started"),
                      () -> {
                        scheduling.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException error) {
                          throw new RuntimeException(error);
                        }
                        visible.set(true);
                      },
                      () -> visible.set(false),
                      error -> {
                        throw error;
                      }));
      try {
        assertThat(scheduling.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.cancelQueued("run")).isTrue();
      } finally {
        release.countDown();
      }
      future.get(5, TimeUnit.SECONDS);
      assertThat(visible).isFalse();
    }
  }

  /** 順序を守る同一プロジェクトおよびについて、待機中実行を取り除くことを確認する。 */
  @Test
  void serializesSameProjectAndRemovesQueuedRunById() {
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

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void differentProjectsProceedAndSuccessorWaitsForFinally() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var other = new CountDownLatch(1);
    var successor = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var queue = new ProjectRunQueue(executor);
      queue.enqueue(
          "first",
          "one",
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException error) {
              throw new RuntimeException(error);
            }
          });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      queue.enqueue("first", "two", successor::countDown);
      queue.enqueue("other", "three", other::countDown);
      try {
        assertThat(other.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.cancelQueued("one")).isFalse();
        assertThat(successor.getCount()).isEqualTo(1);
      } finally {
        release.countDown();
      }
      assertThat(successor.await(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void presenceTracksQueuedAndRunningOperationsUntilSlotRelease() {
    var tasks = new ArrayList<Runnable>();
    var queue = new ProjectRunQueue(tasks::add);
    queue.enqueue(
        "project",
        "first",
        () -> {
          assertThat(queue.containsRun("project", "first")).isTrue();
          assertThat(queue.containsRun("project", "second")).isTrue();
        });
    queue.enqueue("project", "second", () -> {});
    assertThat(queue.containsRun("other", "first")).isFalse();
    assertThat(queue.containsRun("project", "first")).isTrue();
    tasks.removeFirst().run();
    assertThat(queue.containsRun("project", "first")).isFalse();
    assertThat(queue.containsRun("project", "second")).isTrue();
    assertThat(queue.cancelQueued("second")).isTrue();
    assertThat(queue.containsRun("project", "second")).isFalse();
    tasks.removeFirst().run();
    assertThat(queue.containsRun("project", "second")).isFalse();
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void successfulCancellationSurvivesSuccessorExecutorRejection() {
    var tasks = new ArrayList<Runnable>();
    var reject = new java.util.concurrent.atomic.AtomicBoolean();
    var rejected = new java.util.concurrent.atomic.AtomicInteger();
    var queue =
        new ProjectRunQueue(
            task -> {
              if (reject.get()) throw new RejectedExecutionException("closed");
              tasks.add(task);
            });
    queue.enqueue("p", "cancelled", () -> fail("cancelled work ran"));
    queue.enqueue(
        "p",
        "successor",
        () -> fail("rejected work ran"),
        () -> {},
        () -> {},
        error -> rejected.incrementAndGet());
    reject.set(true);
    assertThat(queue.cancelQueued("cancelled")).isTrue();
    assertThat(queue.containsRun("p", "cancelled")).isFalse();
    assertThat(queue.containsRun("p", "successor")).isFalse();
    assertThat(rejected.get()).isEqualTo(1);
    tasks.removeFirst().run();
  }
}
