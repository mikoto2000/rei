package dev.mikoto2000.rei.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ToolCatalogTest {
  static ToolCallback callback(String name) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(name)
            .description(name)
            .inputSchema("{\"type\":\"object\",\"properties\":{}}")
            .build();
      }

      public String call(String arguments) {
        throw new AssertionError("Catalog must not execute tools");
      }
    };
  }

  static List<ToolCallback> all() throws Exception {
    return Files.readAllLines(Path.of("docs/tool-discovery-classification.csv")).stream()
        .skip(1)
        .filter(s -> !s.isBlank())
        .map(s -> callback(s.split(",")[0]))
        .toList();
  }

  @Test
  void preservesAll184NamesAndPublishesExactly31Basics() throws Exception {
    var catalog = ToolCatalog.standard(all());
    assertThat(catalog.names()).hasSize(184);
    assertThat(catalog.basicNames())
        .hasSize(31)
        .contains("runCommand", "killShellProcess", "readRawToolResult");
    assertThat(catalog.basicNames()).doesNotContain("googleCalendarCreateEvent", "beginReview");
  }

  @Test
  void refusesDuplicateIdentityRatherThanSilentlyDeduplicating() {
    assertThatThrownBy(() -> ToolCatalog.standard(List.of(callback("now"), callback("now"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate");
  }

  @Test
  void refusesNewUnclassifiedToolsInsteadOfInferringTheirLifecycle() {
    assertThatThrownBy(() -> ToolCatalog.standard(List.of(callback("newStatefulOperation"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unclassified");
  }

  @Test
  void dependenciesOfDiagnosedRepairIncludeItsGuardedChangeSet() throws Exception {
    var c = ToolCatalog.standard(all());
    assertThat(c.toolsForSets(Set.of(c.operationSetFor("proposeDiagnosedRepair"))))
        .contains(
            "applyDiagnosedRepair",
            "inspectDiagnosedRepair",
            "reconcileTextChangeSet",
            "discardTextChangeSet");
  }

  static List<ToolCallback> realDefinitions() throws Exception {
    var rows =
        Files.readAllLines(Path.of("docs/tool-discovery-classification.csv")).stream()
            .skip(1)
            .filter(x -> !x.isBlank())
            .map(x -> x.split(","))
            .toList();
    var allowed = rows.stream().map(x -> x[0]).collect(java.util.stream.Collectors.toSet());
    var classes =
        rows.stream()
            .map(x -> x[3])
            .filter(x -> !x.endsWith("ReviewToolProfile"))
            .distinct()
            .toList();
    var callbacks = new java.util.ArrayList<ToolCallback>();
    for (String type : classes) {
      Object object = org.mockito.Mockito.mock(Class.forName(type));
      var provider =
          org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
              .toolObjects(object)
              .build();
      for (var callback : provider.getToolCallbacks())
        if (allowed.contains(callback.getToolDefinition().name())) callbacks.add(callback);
    }
    var prompt =
        new org.springframework.ai.chat.prompt.Prompt(
            "fixture",
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .toolCallbacks(callbacks)
                .build());
    var installed = dev.mikoto2000.rei.core.stagnation.ReviewToolProfile.install(prompt);
    return ((org.springframework.ai.model.tool.ToolCallingChatOptions) installed.getOptions())
        .getToolCallbacks();
  }
}
