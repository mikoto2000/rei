package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaskStateTest {

  @Test
  void newTaskStateIsEmpty() {
    TaskState state = new TaskState();
    assertTrue(state.isEmpty());
    assertEquals(TaskState.STATUS_IDLE, state.status());
    assertEquals("", state.goal());
    assertTrue(state.completed().isEmpty());
    assertTrue(state.pending().isEmpty());
  }

  @Test
  void goalCanBeSet() {
    TaskState state = new TaskState();
    state.start("UserService の null handling を修正する");
    assertEquals("UserService の null handling を修正する", state.goal());
    assertEquals(TaskState.STATUS_IN_PROGRESS, state.status());
    assertFalse(state.isEmpty());
  }

  @Test
  void statusCanBeChanged() {
    TaskState state = new TaskState();
    state.start("goal");
    state.setStatus(TaskState.STATUS_BLOCKED);
    assertEquals(TaskState.STATUS_BLOCKED, state.status());
  }

  @Test
  void completedCanBeAdded() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addCompleted("再現テストを追加した");
    assertEquals(List.of("再現テストを追加した"), state.completed());
  }

  @Test
  void pendingCanBeAdded() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addPending("実装修正");
    assertEquals(List.of("実装修正"), state.pending());
  }

  @Test
  void duplicateCompletedIsNotAddedTwice() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addCompleted("テスト追加");
    state.addCompleted("テスト追加");
    assertEquals(1, state.completed().size());
  }

  @Test
  void duplicatePendingIsNotAddedTwice() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addPending("テスト実行");
    state.addPending("テスト実行");
    assertEquals(1, state.pending().size());
  }

  @Test
  void completeClearsPendingAndSetsCompleted() {
    TaskState state = new TaskState();
    state.start("goal");
    state.addPending("残り作業");
    state.complete();
    assertEquals(TaskState.STATUS_COMPLETED, state.status());
    assertTrue(state.pending().isEmpty());
  }

  @Test
  void blockSetsBlockedStatus() {
    TaskState state = new TaskState();
    state.start("goal");
    state.block();
    assertEquals(TaskState.STATUS_BLOCKED, state.status());
  }

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

  @Test
  void emptyTaskStateRendersBlank() {
    TaskState state = new TaskState();
    assertEquals("", state.renderForPrompt());
  }

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
