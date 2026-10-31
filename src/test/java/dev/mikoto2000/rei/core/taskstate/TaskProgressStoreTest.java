package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.context.RunIdentity;
import dev.mikoto2000.rei.context.TaskProgressStore;
import dev.mikoto2000.rei.context.TaskStateSnapshot;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TaskProgressStoreTest {
  @Test
  void newRunsNeverInheritPreviousProjectStepsAndKeepAllRecordedSteps() {
    var store = new TaskProgressStore();
    var tool = new TaskStateTools(new TaskState());
    tool.setProgress(store);
    try {
      RunIdentity.setCurrentRunId("old");
      tool.updateTaskState("以前の依頼", "IN_PROGRESS", List.of("以前の完了工程"), List.of("以前の残り工程"));
      RunIdentity.setCurrentRunId("new");
      var steps = java.util.stream.IntStream.rangeClosed(1, 30).mapToObj(i -> "工程" + i).toList();
      tool.updateTaskState("新しい依頼", "IN_PROGRESS", List.of(), steps);
      assertEquals(steps, store.get("new").pending());
      assertTrue(store.get("new").completed().isEmpty());
      tool.updateTaskState(null, "COMPLETED", List.of("工程1"), List.of());
      assertEquals(
          29, store.get("new").pending().size(), "Completion alone does not invent finished steps");
      assertEquals(List.of("工程1"), store.get("new").completed());
    } finally {
      RunIdentity.setCurrentRunId(null);
    }
  }

  @Test
  void toolPublishesImmutableProgressUnderExecutingRunAndClearsIdle() throws Exception {
    var store = new TaskProgressStore();
    var state = new TaskState();
    var tool = new TaskStateTools(state);
    tool.setProgress(store);
    var changes = new AtomicInteger();
    try (var subscription = store.onChange(changes::incrementAndGet)) {
      RunIdentity.setCurrentRunId("run-a");
      tool.updateTaskState("目的", "IN_PROGRESS", List.of(), List.of("調査", "実装", "検証"));
      var initial = store.get("run-a");
      tool.updateTaskState(null, "IN_PROGRESS", List.of("調査"), List.of());
      assertEquals(List.of("調査", "実装", "検証"), initial.pending());
      assertEquals(List.of("調査"), store.get("run-a").completed());
      assertEquals(List.of("実装", "検証"), store.get("run-a").pending());
      store.update("run-b", new TaskStateSnapshot("別の目的", "BLOCKED", List.of(), List.of("確認")));
      tool.updateTaskState(null, "IDLE", List.of(), List.of());
      assertNull(store.get("run-a"));
      assertEquals("BLOCKED", store.get("run-b").status());
      assertEquals(4, changes.get());
    } finally {
      RunIdentity.setCurrentRunId(null);
    }
    store.remove("run-b");
    assertNull(store.get("run-b"));
    assertEquals(4, changes.get(), "Unsubscribe prevents later redraws");
  }

  @Test
  void unscopedAndRejectedUpdatesDoNotBecomeVisible() {
    var store = new TaskProgressStore();
    var tool = new TaskStateTools(new TaskState());
    tool.setProgress(store);
    tool.updateTaskState("目的", "IN_PROGRESS", List.of(), List.of("調査"));
    assertNull(store.get(null));
    try {
      RunIdentity.setCurrentRunId("run");
      tool.updateTaskState(null, "INVALID", List.of(), List.of());
      assertNull(store.get("run"));
    } finally {
      RunIdentity.setCurrentRunId(null);
    }
  }
}
