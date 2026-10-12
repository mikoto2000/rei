package dev.mikoto2000.rei.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

class TaskTagContractTest {

  @Test
  void createToolSchemaOmitsRemovedTags() throws Exception {
    assertToolFields("taskCreate", Set.of("title", "dueDate", "priority"));
  }

  @Test
  void updateToolSchemaOmitsRemovedTags() throws Exception {
    assertToolFields("taskUpdate", Set.of("id", "title", "dueDate", "priority"));
  }

  @Test
  void taskAndQueryRecordsDoNotExposeTags() {
    assertEquals(Set.of("id", "title", "dueDate", "priority", "status", "createdAt", "completedAt"),
        recordFields(Task.class));
    assertEquals(Set.of("priority", "dueBefore"), recordFields(TaskQuery.class));
  }

  private void assertToolFields(String name, Set<String> expected) throws Exception {
    var definition = Arrays.stream(MethodToolCallbackProvider.builder()
        .toolObjects(new TaskTools(mock(TaskService.class)))
        .build().getToolCallbacks())
        .map(callback -> callback.getToolDefinition())
        .filter(candidate -> name.equals(candidate.name()))
        .findFirst().orElseThrow();
    var properties = new ObjectMapper().readTree(definition.inputSchema()).path("properties");
    Set<String> fields = new java.util.HashSet<>();
    properties.fieldNames().forEachRemaining(fields::add);
    assertEquals(expected, fields);
    assertFalse(definition.description().contains("tags"));
  }

  private Set<String> recordFields(Class<?> type) {
    return Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).collect(Collectors.toSet());
  }
}
