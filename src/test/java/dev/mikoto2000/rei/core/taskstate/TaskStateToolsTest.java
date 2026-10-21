package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** タスク状態ツール一覧の結果と状態の契約を検証するテスト。 */
class TaskStateToolsTest {

  /** 検証に使う状態。 */
  private final TaskState state = new TaskState();

  /** 検証に使うツール一覧。 */
  private final TaskStateTools tools = new TaskStateTools(state);

  /** 新規タスクを開始することを確認する。 */
  @Test
  void startsNewTaskWithGoal() {
    tools.updateTaskState(
        "UserService の null handling を修正する",
        TaskState.STATUS_IN_PROGRESS,
        List.of("再現テストを追加した"),
        List.of("実装修正"));
    assertEquals("UserService の null handling を修正する", state.goal());
    assertEquals(TaskState.STATUS_IN_PROGRESS, state.status());
    assertEquals(List.of("再現テストを追加した"), state.completed());
    assertEquals(List.of("実装修正"), state.pending());
  }

  /** 作業状態の記録で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void continuesExistingTaskWithoutGoal() {
    tools.updateTaskState("goal", TaskState.STATUS_IN_PROGRESS, List.of(), List.of());
    tools.updateTaskState(null, TaskState.STATUS_IN_PROGRESS, List.of("テスト実行"), List.of("残り"));
    assertEquals("goal", state.goal());
    assertEquals(List.of("テスト実行"), state.completed());
    assertEquals(List.of("残り"), state.pending());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completesTaskAndClearsPending() {
    tools.updateTaskState("goal", TaskState.STATUS_IN_PROGRESS, List.of(), List.of("残り"));
    tools.updateTaskState(null, TaskState.STATUS_COMPLETED, List.of("残り"), List.of());
    assertEquals(TaskState.STATUS_COMPLETED, state.status());
    assertTrue(state.pending().isEmpty());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void blocksTask() {
    tools.updateTaskState("goal", TaskState.STATUS_IN_PROGRESS, List.of(), List.of());
    tools.updateTaskState(null, TaskState.STATUS_BLOCKED, List.of(), List.of());
    assertEquals(TaskState.STATUS_BLOCKED, state.status());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resetsToIdle() {
    tools.updateTaskState("goal", TaskState.STATUS_IN_PROGRESS, List.of("完了"), List.of("予定"));
    tools.updateTaskState(null, TaskState.STATUS_IDLE, List.of(), List.of());
    assertTrue(state.isEmpty());
    assertEquals(TaskState.STATUS_IDLE, state.status());
  }

  /** 不明状態を受け付けないことを確認する。 */
  @Test
  void rejectsUnknownStatus() {
    String result = tools.updateTaskState("goal", "UNKNOWN", List.of(), List.of());
    assertTrue(result.contains("不正な status"));
  }

  /** 未指定目標を受け付けないことを確認する。 */
  @Test
  void rejectsMissingGoalWhenIdle() {
    String result = tools.updateTaskState(null, TaskState.STATUS_IN_PROGRESS, List.of(), List.of());
    assertTrue(result.contains("goal は必須"));
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void toolDefinitionRequiresOnlyStatusAndExplainsStringArrays() throws Exception {
    var definition =
        MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build()
            .getToolCallbacks()[0]
            .getToolDefinition();
    var schema =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(definition.inputSchema());

    assertEquals(
        List.of("status"),
        new com.fasterxml.jackson.databind.ObjectMapper()
            .convertValue(
                schema.get("required"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}));
    for (String field : List.of("completed", "pending")) {
      var property = schema.path("properties").path(field);
      assertEquals("array", property.path("type").asText());
      assertEquals("string", property.path("items").path("type").asText());
      assertTrue(property.path("description").asText().contains("JSON 配列"));
      assertTrue(property.path("description").asText().contains("[] または省略"));
      assertTrue(property.path("description").asText().contains("カンマ区切り文字列は不可"));
    }
    assertTrue(
        schema.path("properties").path("goal").path("description").asText().contains("省略可能"));
    assertTrue(
        schema
            .path("properties")
            .path("status")
            .path("description")
            .asText()
            .contains("IN_PROGRESS"));
    assertTrue(definition.description().contains("\"pending\":[\"レビュー委譲\",\"指摘の検証\"]"));
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void generatedCallbackAcceptsOmittedGoalAndOptionalListsForAnExistingTask() {
    var callback =
        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()[0];
    callback.call("{\"goal\":\"goal\",\"status\":\"IN_PROGRESS\"}");
    callback.call("{\"status\":\"IN_PROGRESS\",\"completed\":null,\"pending\":[]}");

    assertEquals("goal", state.goal());
    assertEquals(TaskState.STATUS_IN_PROGRESS, state.status());
    assertTrue(state.completed().isEmpty());
    assertTrue(state.pending().isEmpty());
  }
}
