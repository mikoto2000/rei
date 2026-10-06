package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
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
  @Test void evidenceToolsAreOptInAndMustBeUniqueRequestedTools() throws Exception {
    assertThat(loader.load(write("a.yaml",yaml("reviewer"))).evidenceTools()).isEmpty();
    assertThat(loader.load(write("a.yaml",yaml("reviewer")+"evidenceTools: [readMultiFile]\n")).evidenceTools()).containsExactly("readMultiFile");
    for (String value : List.of("[missing]","[readMultiFile, readMultiFile]","wrong","[true]")) {
      var file=write("invalid.yaml",yaml("reviewer")+"evidenceTools: "+value+"\n");
      assertThatThrownBy(()->loader.load(file)).hasMessageContaining("evidenceTools");
    }
  }
  @Test void requiredCallsLoadExactJsonArgumentsAndRejectUnobservedToolsAndUnsafeValues() throws Exception {
    String extra="evidenceTools: [readMultiFile]\nrequiredToolCalls:\n  - tool: readMultiFile\n    arguments: {paths: [README.md]}\n";
    var definition=loader.load(write("calls.yaml",yaml("reviewer")+extra));
    assertThat(definition.requiredToolCalls()).hasSize(1);
    assertThat(definition.requiredToolCalls().getFirst().argumentsJson()).isEqualTo("{\"paths\":[\"README.md\"]}");
    assertThat(loader.load(write("default.yaml",yaml("reviewer"))).requiredToolCalls()).isEmpty();
    for(String bad:List.of(extra.replace("tool: readMultiFile","tool: missing"),extra.replace("arguments: {paths: [README.md]}","arguments: []"),extra.replace("arguments: {paths: [README.md]}","arguments: {date: 2026-01-01}"),extra.replace("evidenceTools: [readMultiFile]\n",""))) {
      var file=write("bad-calls.yaml",yaml("reviewer")+bad);
      assertThatThrownBy(()->loader.load(file)).hasMessageContaining("requiredToolCalls");
    }
  }
  @Test void repairLimitDefaultsToDisabledAndRejectsInvalidValues() throws Exception {
    assertThat(loader.load(write("a.yaml",yaml("reviewer"))).maxRepairs()).isZero();
    assertThat(loader.load(write("a.yaml",yaml("reviewer")+"maxRepairs: 3\n")).maxRepairs()).isEqualTo(3);
    for (String value : List.of("-1","4","true","null","1.5","wrong")) {
      var file=write("invalid.yaml",yaml("reviewer")+"maxRepairs: "+value+"\n");
      assertThatThrownBy(()->loader.load(file)).hasMessageContaining("maxRepairs");
    }
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
    assertThat(samples.list()).extracting(SubAgentDefinition::id).containsExactly("document-editor", "researcher", "reviewer");
  }
}
