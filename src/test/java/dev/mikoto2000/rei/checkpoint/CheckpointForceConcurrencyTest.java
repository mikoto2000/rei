package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.core.actionplan.ActionPlan;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.checkpoint.CheckpointStore;
import dev.mikoto2000.rei.core.taskstate.TaskState;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.sqlite.SQLiteDataSource;

/** 再開用の記録強制同時実行数の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class CheckpointForceConcurrencyTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う保存と検索の窓口。 */
  private PersistentCheckpointRepository repository;

  /** 検証に使うサービス。 */
  private PersistentCheckpointService service;

  /** 検証に使う通知の連携先。 */
  private final InspectionBus bus = new InspectionBus();

  /** 検証に使う待機中。 */
  private final List<Runnable> queued = new ArrayList<>();

  /** 検証に使う所有者。 */
  private AgentRunContext owner;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("concurrency.db"));
    var settings = new CheckpointProperties();
    repository = new PersistentCheckpointRepository(source, settings);
    var beans = new StaticListableBeanFactory();
    beans.addBean(
        "router", new ConversationInputRouter(queued::add, (context, prompt, interventions) -> {}));
    service =
        new PersistentCheckpointService(
            repository,
            new CheckpointReconciler(null),
            settings,
            bus,
            beans.getBeanProvider(ConversationInputRouter.class),
            beans.getBeanProvider(RunRegistry.class),
            beans.getBeanProvider(RunService.class),
            beans.getBeanProvider(ActionPlan.class),
            beans.getBeanProvider(WorkingSet.class),
            beans.getBeanProvider(TaskState.class),
            beans.getBeanProvider(CheckpointStore.class));
    bus.service = service;
    owner = new AgentRunContext("run", "session", root, "project");
    service.start(owner, "original request");
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    service.close();
  }

  /** 作業の中断と再開で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void toolBoundaryPublishesAfterReleasingCheckpointLock() {
    bus.inspectOn(AgentEventType.CHECKPOINT_REVISION_SAVED);

    bus.publishBoundary(
        new AgentEventFactory(Clock.systemUTC())
            .toolStarted("read", "readFile", "read")
            .withOwnership(owner));

    assertEquals(1, bus.inspections.get());
    assertEquals("read", saved().operations().getFirst().toolCallId());
    assertTrue(repository.historyUsage().revisions() > 1);
  }

  /** 作業の中断と再開で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void finishPublishesAfterReleasingCheckpointLock() {
    bus.inspectOn(AgentEventType.CHECKPOINT_REVISION_SAVED);

    service.finish(owner, "INTERRUPTED");

    assertEquals(1, bus.inspections.get());
    assertEquals("INTERRUPTED", saved().status());
  }

  /** 作業の中断と再開で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resumePublishesAfterReleasingCheckpointLock() {
    service.finish(owner, "INTERRUPTED");
    var previous = saved();
    bus.inspectOn(AgentEventType.CHECKPOINT_RESUMED);

    var resumed = service.resume("project", previous.taskId(), AgentRunContext.RequestSource.SHELL);

    assertEquals(1, bus.inspections.get());
    assertEquals(resumed.runId(), saved().runId());
    assertEquals(1, queued.size());
  }

  /** 作業の中断と再開で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void goalContinuationPublishesAfterReleasingCheckpointLock() {
    service.finish(owner, "INTERRUPTED");
    var previous = saved();
    var next = new AgentRunContext("goal-run", "session", root, "project");
    bus.inspectOn(AgentEventType.CHECKPOINT_RESUMED);

    service.prepareGoalContinuation(next, previous.taskId(), owner.runId(), previous.revision());

    assertEquals(1, bus.inspections.get());
    assertEquals(next.runId(), saved().runId());
  }

  /** {@code saved} の条件を再現するための補助情報を返す。 */
  private PersistentCheckpoint saved() {
    return repository.list("project").getFirst();
  }

  /**
   * 検証条件を再現するためのInspection通知の連携先。
   *
   * <p>Observers may inspect checkpoint history from another thread before notification returns.
   */
  private static final class InspectionBus extends InMemoryAgentEventBus {
    /** 検証に使うinspection種別。 */
    private final AtomicReference<AgentEventType> inspectionType = new AtomicReference<>();

    /** 検証に使うinspections。 */
    private final AtomicInteger inspections = new AtomicInteger();

    /** 検証に使うサービス。 */
    private PersistentCheckpointService service;

    /** {@code inspectOn} の条件を再現するため、検証用の操作や状態を扱う。 */
    void inspectOn(AgentEventType type) {
      inspectionType.set(type);
    }

    /** 検証用に、状態や結果を購読先へ通知する。 */
    @Override
    public void publish(AgentEvent event) {
      if (inspectionType.compareAndSet(event.type(), null)) {
        var inspection = new FutureTask<>(() -> service.cleanupHistory(false, true));
        Thread.startVirtualThread(inspection);
        try {
          assertTrue(inspection.get(2, TimeUnit.SECONDS).force());
          inspections.incrementAndGet();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          fail("Checkpoint inspection was interrupted", interrupted);
        } catch (Exception failure) {
          fail(
              "Checkpoint publication held the checkpoint lock while notifying observers", failure);
        }
      }
      super.publish(event);
    }
  }
}
