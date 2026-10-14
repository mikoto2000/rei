package dev.mikoto2000.rei.checkpoint;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("integration")
class CheckpointForceConcurrencyTest {
  @TempDir Path root;
  private PersistentCheckpointRepository repository;
  private PersistentCheckpointService service;
  private final InspectionBus bus = new InspectionBus();
  private final List<Runnable> queued = new ArrayList<>();
  private AgentRunContext owner;

  @BeforeEach
  void setup() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("concurrency.db"));
    var settings = new CheckpointProperties();
    repository = new PersistentCheckpointRepository(source, settings);
    var beans = new StaticListableBeanFactory();
    beans.addBean("router", new ConversationInputRouter(queued::add, (context, prompt, interventions) -> {}));
    service = new PersistentCheckpointService(repository, new CheckpointReconciler(null), settings, bus,
        beans.getBeanProvider(ConversationInputRouter.class), beans.getBeanProvider(RunRegistry.class),
        beans.getBeanProvider(RunService.class), beans.getBeanProvider(ActionPlan.class),
        beans.getBeanProvider(WorkingSet.class), beans.getBeanProvider(TaskState.class),
        beans.getBeanProvider(CheckpointStore.class));
    bus.service = service;
    owner = new AgentRunContext("run", "session", root, "project");
    service.start(owner, "original request");
  }

  @AfterEach
  void close() {
    service.close();
  }

  @Test
  void toolBoundaryPublishesAfterReleasingCheckpointLock() {
    bus.inspectOn(AgentEventType.CHECKPOINT_REVISION_SAVED);

    bus.publishBoundary(new AgentEventFactory(Clock.systemUTC())
        .toolStarted("read", "readFile", "read").withOwnership(owner));

    assertEquals(1, bus.inspections.get());
    assertEquals("read", saved().operations().getFirst().toolCallId());
    assertTrue(repository.historyUsage().revisions() > 1);
  }

  @Test
  void finishPublishesAfterReleasingCheckpointLock() {
    bus.inspectOn(AgentEventType.CHECKPOINT_REVISION_SAVED);

    service.finish(owner, "INTERRUPTED");

    assertEquals(1, bus.inspections.get());
    assertEquals("INTERRUPTED", saved().status());
  }

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

  private PersistentCheckpoint saved() {
    return repository.list("project").getFirst();
  }

  /** Observers may inspect checkpoint history from another thread before notification returns. */
  private static final class InspectionBus extends InMemoryAgentEventBus {
    private final AtomicReference<AgentEventType> inspectionType = new AtomicReference<>();
    private final AtomicInteger inspections = new AtomicInteger();
    private PersistentCheckpointService service;

    void inspectOn(AgentEventType type) {
      inspectionType.set(type);
    }

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
          fail("Checkpoint publication held the checkpoint lock while notifying observers", failure);
        }
      }
      super.publish(event);
    }
  }
}
