package dev.mikoto2000.rei.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** タスクタグ契約の結果と状態の契約を検証するテスト。 */
class TaskTagContractTest {

  /** タスクの管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void createToolSchemaOmitsRemovedTags() throws Exception {
    assertToolFields("taskCreate", Set.of("title", "dueDate", "priority"));
  }

  /** タスクの管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void updateToolSchemaOmitsRemovedTags() throws Exception {
    assertToolFields("taskUpdate", Set.of("id", "title", "dueDate", "priority"));
  }

  /** タスクの管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void taskAndQueryRecordsDoNotExposeTags() {
    assertEquals(
        Set.of("id", "title", "dueDate", "priority", "status", "createdAt", "completedAt"),
        recordFields(Task.class));
    assertEquals(Set.of("priority", "dueBefore"), recordFields(TaskQuery.class));
  }

  /** 複数の検証で共通する期待結果を確認し、要件に合わない状態を検出する。 */
  private void assertToolFields(String name, Set<String> expected) throws Exception {
    var definition =
        Arrays.stream(
                MethodToolCallbackProvider.builder()
                    .toolObjects(new TaskTools(mock(TaskService.class)))
                    .build()
                    .getToolCallbacks())
            .map(callback -> callback.getToolDefinition())
            .filter(candidate -> name.equals(candidate.name()))
            .findFirst()
            .orElseThrow();
    var properties = new ObjectMapper().readTree(definition.inputSchema()).path("properties");
    Set<String> fields = new java.util.HashSet<>();
    properties.fieldNames().forEachRemaining(fields::add);
    assertEquals(expected, fields);
    assertFalse(definition.description().contains("tags"));
  }

  /** 検証用に、後から経緯や使用量を確認できるよう、項目一覧を記録する。 */
  private Set<String> recordFields(Class<?> type) {
    return Arrays.stream(type.getRecordComponents())
        .map(component -> component.getName())
        .collect(Collectors.toSet());
  }
}
