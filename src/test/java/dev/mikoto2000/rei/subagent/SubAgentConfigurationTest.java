package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubAgentConfigurationTest {
  @TempDir Path directory;
  final SubAgentToolPolicy policy = new SubAgentToolPolicy(Set.of("readMultiFile", "runCommand", "delegateTask"));
  final SubAgentDefinitionLoader loader = new SubAgentDefinitionLoader(policy, model -> model.equals("known"));
  static String yaml(String id) {
    return """
        id: %s
        name: Reviewer
        description: Independent review
        systemPrompt: |
          Review independently.
        tools: [readMultiFile]
        maxSteps: 2
        timeout: 120s
        """.formatted(id);
  }
  Path write(String file, String yaml) throws Exception {
    return Files.writeString(directory.resolve(file), yaml);
  }
  @Test void loadsSafeDefinitionWithOptionalModel() throws Exception {
    var definition = loader.load(write("a.yaml", yaml("reviewer")));
    assertThat(definition.id()).isEqualTo("reviewer");
    assertThat(definition.model()).isNull();
    assertThat(definition.timeout()).isEqualTo(java.time.Duration.ofSeconds(120));
    assertThat(loader.load(write("b.yaml", yaml("reviewer") + "model: known\n")).model()).isEqualTo("known");
  }
  @Test void rejectsInvalidFieldsWithFileAndField() throws Exception {
    for (String field : List.of("id", "name", "description", "systemPrompt", "maxSteps", "timeout")) {
      String bad = yaml("reviewer").replaceAll("(?m)^" + field + ":.*\\n(?:  .*\\n)*", "");
      Path path = write("invalid.yaml", bad);
      assertThatThrownBy(() -> loader.load(path)).hasMessageContaining("invalid.yaml").hasMessageContaining(field);
    }
    for (String replacement : List.of("0", "-1", "wrong")) {
      Path path = write("invalid.yaml", yaml("reviewer").replace("maxSteps: 2", "maxSteps: " + replacement));
      assertThatThrownBy(() -> loader.load(path)).hasMessageContaining("maxSteps");
    }
    for (String replacement : List.of("0s", "-1s", "forever", "999999999999999999999s")) {
      Path path = write("invalid.yaml", yaml("reviewer").replace("120s", replacement));
      assertThatThrownBy(() -> loader.load(path)).hasMessageContaining("timeout");
    }
  }
  @Test void rejectsUnknownProhibitedAndRecursiveToolsAndUnknownModels() throws Exception {
    for (String tool : List.of("missing", "runCommand", "delegateTask")) {
      Path file = write("invalid.yaml", yaml("reviewer").replace("readMultiFile", tool));
      assertThatThrownBy(() -> loader.load(file)).hasMessageContaining("tools").hasMessageContaining(tool);
    }
    assertThatThrownBy(() -> loader.load(write("invalid.yaml", yaml("reviewer") + "model: missing\n")))
        .hasMessageContaining("model");
  }
  @Test void rejectsUnknownKeysDuplicateKeysAndJavaTags() throws Exception {
    for (String extra : List.of("implementation: arbitrary\n", "null: value\n", "id: other\n", "model: !!java.net.URL [https://example.com]\n")) {
      assertThatThrownBy(() -> loader.load(write("invalid.yaml", yaml("reviewer") + extra)))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("invalid.yaml");
    }
  }
  @Test void policyIntersectionIsFailClosed() {
    assertThat(policy.effectiveTools(List.of("readMultiFile", "runCommand", "delegateTask", "missing")))
        .containsExactly("readMultiFile");
  }
  @Test void registryReloadIsAtomicAndOrdered() throws Exception {
    write("z.yaml", yaml("z"));
    var registry = new SubAgentRegistry(directory, loader);
    assertThat(registry.reload()).isEmpty();
    assertThat(registry.findById("z")).isPresent();
    write("a.yaml", yaml("a"));
    assertThat(registry.reload()).isEmpty();
    assertThat(registry.list()).extracting(SubAgentDefinition::id).containsExactly("a", "z");
    write("broken.yaml", yaml("z"));
    assertThat(registry.reload()).anyMatch(error -> error.contains("duplicate id") && error.contains("broken.yaml"));
    assertThat(registry.list()).extracting(SubAgentDefinition::id).containsExactly("a", "z");
    Files.delete(directory.resolve("broken.yaml"));
    Files.delete(directory.resolve("z.yaml"));
    assertThat(registry.reload()).isEmpty();
    assertThat(registry.findById("z")).isEmpty();
  }
  @Test void bundledSamplesOnlyRequestActualAuditedTools() {
    var catalog = new SubAgentToolCatalog(
        org.mockito.Mockito.mock(dev.mikoto2000.rei.search.SearchTools.class),
        org.mockito.Mockito.mock(dev.mikoto2000.rei.websearch.WebSearchTools.class));
    var samples = new SubAgentRegistry(Path.of("config/subagents"),
        new SubAgentDefinitionLoader(new SubAgentToolPolicy(catalog.knownNames()), m -> false));
    assertThat(samples.reload()).isEmpty();
    assertThat(samples.list()).extracting(SubAgentDefinition::id).containsExactly("researcher", "reviewer");
  }
}
