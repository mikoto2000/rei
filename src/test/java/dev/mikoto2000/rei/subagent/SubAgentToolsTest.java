package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubAgentToolsTest {
  @TempDir Path directory;
  @Test void delegateReturnsTypedStatusesAndDynamicCatalogWithoutPrompts() throws Exception {
    var loader = new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of("readMultiFile")), m -> true);
    var registry = new SubAgentRegistry(directory, loader);
    var runner = mock(SubAgentRunner.class);
    var tools = new SubAgentTools(runner, registry);
    var callback = tools.callback();
    Files.writeString(directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer"));
    registry.reload();
    assertThat(callback.getToolDefinition().description()).contains("reviewer", "Independent review").doesNotContain("Review independently.");
    for (var status : SubAgentResult.Status.values()) {
      when(runner.run("reviewer", "task", null)).thenReturn(new SubAgentResult("reviewer", "run", status, "output", Instant.now(), Instant.now()));
      assertThat(callback.call("{\"agent\":\"reviewer\",\"task\":\"task\"}")).contains(status.name());
    }
    verify(runner, times(SubAgentResult.Status.values().length)).run("reviewer", "task", null);
  }
}
