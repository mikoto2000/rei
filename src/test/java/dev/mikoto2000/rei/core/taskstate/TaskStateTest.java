package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** タスク状態の結果と状態の契約を検証するテスト。 */
class TaskStateTest {

  @Test
  void completedWorkIsRemovedFromPendingAndCannotBeAddedBackByTheSameUpdate() {
    var state = new TaskState();
    state.start("review docs");
    state.addPending("review introduction");
    state.addPending("review examples");
    state.addCompleted("review introduction");
    state.addPending("review introduction");
    assertEquals(List.of("review introduction"), state.completed());
    assertEquals(List.of("review examples"), state.pending());
    var restored = new TaskState();
    restored.restore(state.snapshot());
    assertEquals(state.snapshot(), restored.snapshot());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void newTaskStateIsEmpty() {
    TaskState state = new TaskState();
    assertTrue(state.isEmpty());
    assertEquals(TaskState.STATUS_IDLE, state.status());
    assertEquals("", state.goal());
    assertTrue(state.completed().isEmpty());
    assertTrue(state.pending().isEmpty());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void goalCanBeSet() {
    TaskState state = new TaskState();
    state.start("UserService の null handling を修正する");
    assertEquals("UserService の null handling を修正する", state.goal());
    assertEquals(TaskState.STATUS_IN_PROGRESS, state.status());
    assertFalse(state.isEmpty());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void statusCanBeChanged() {
    TaskState state = new TaskState();
    state.start("goal");
    state.setStatus(TaskState.STATUS_BLOCKED);
    assertEquals(TaskState.STATUS_BLOCKED, state.status());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completedCanBeAdded() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addCompleted("再現テストを追加した");
    assertEquals(List.of("再現テストを追加した"), state.completed());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void pendingCanBeAdded() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addPending("実装修正");
    assertEquals(List.of("実装修正"), state.pending());
  }

  /** 作業状態の記録で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateCompletedIsNotAddedTwice() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addCompleted("テスト追加");
    state.addCompleted("テスト追加");
    assertEquals(1, state.completed().size());
  }

  /** 作業状態の記録で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicatePendingIsNotAddedTwice() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addPending("テスト実行");
    state.addPending("テスト実行");
    assertEquals(1, state.pending().size());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completeClearsPendingAndSetsCompleted() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addPending("残り作業");
    state.complete();
    assertEquals(TaskState.STATUS_COMPLETED, state.status());
    assertTrue(state.pending().isEmpty());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void blockSetsBlockedStatus() {
    TaskState state = new TaskState();
    state.start("goal");
    state.block();
    assertEquals(TaskState.STATUS_BLOCKED, state.status());
  }

  /** 初期状態への復帰について、新規タスクを開始することを確認する。 */
  @Test
  void resetStartsNewTask() {
    TaskState state = new TaskState();
    state.start("旧タスク");
    state.addCompleted("完了");
    state.addPending("予定");
    state.reset();
    assertTrue(state.isEmpty());
    assertEquals(TaskState.STATUS_IDLE, state.status());
    assertTrue(state.completed().isEmpty());
    assertTrue(state.pending().isEmpty());
  }

  /** 作業状態の記録で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void renderForPromptContainsGoalAndStatus() {
    TaskState state = new TaskState();
    state.start("UserService の null handling を修正する");
    state.addCompleted("再現テストを追加した");
    state.addPending("実装修正");
    String prompt = state.renderForPrompt();
    assertTrue(prompt.contains("## Current Task"));
    assertTrue(prompt.contains("UserService の null handling を修正する"));
    assertTrue(prompt.contains("IN_PROGRESS"));
    assertTrue(prompt.contains("再現テストを追加した"));
    assertTrue(prompt.contains("実装修正"));
  }

  /** 作業状態の記録で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void emptyTaskStateRendersBlank() {
    TaskState state = new TaskState();
    assertEquals("", state.renderForPrompt());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void evictsOldestWhenMaxExceeded() {
    TaskState state = new TaskState(3);
    state.start("goal");
    state.addCompleted("a");
    state.addCompleted("b");
    state.addCompleted("c");
    state.addCompleted("d");
    assertEquals(List.of("b", "c", "d"), state.completed());
  }
}
