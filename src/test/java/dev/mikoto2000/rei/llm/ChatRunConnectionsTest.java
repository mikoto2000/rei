package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatRunConnectionsTest {
  @TempDir Path root;

  @Test
  void changingSelectionOnlyAffectsNewRunsAndNeverResamplesAnAcceptedRun() {
    var connections = connections();
    var first = owner("run-one", "session");
    var accepted = connections.accept(first);
    connections.select("second");
    assertThat(connections.selectedEndpoint()).isEqualTo("second");
    assertThat(connections.accept(first)).isSameAs(accepted);
    assertThat(accepted.profileId()).isEqualTo("first");
    assertThat(connections.accept(owner("run-two", "session")).profileId()).isEqualTo("second");
  }

  @Test
  void modelOverridesBelongToEachEndpointAndDoNotChangeAcceptedModels() {
    var connections = connections();
    var original = connections.accept(owner("run-zero", "session"));
    connections.selectModel("first-override");
    assertThat(connections.accept(owner("run-one", "session")).endpoint().model()).isEqualTo("first-override");
    connections.select("second");
    connections.selectModel("second-override");
    assertThat(connections.accept(owner("run-two", "session")).endpoint().model()).isEqualTo("second-override");
    connections.select("first");
    assertThat(connections.accept(owner("run-three", "session")).endpoint().model()).isEqualTo("first-override");
    assertThat(original.endpoint().model()).isEqualTo("first-model");
  }

  @Test
  void unknownEndpointCannotChangeSelectionOrFallBack() {
    var connections = connections();
    assertThatThrownBy(() -> connections.select("PRIVATE_CANARY"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint-name")
        .hasMessageNotContaining("PRIVATE_CANARY");
    assertThat(connections.selectedEndpoint()).isEqualTo("first");
  }

  @Test
  void anotherSessionCannotReuseTheSameRunConnection() {
    var connections = connections();
    connections.accept(owner("same-run", "original-session"));
    assertThatThrownBy(() -> connections.accept(owner("same-run", "other-session")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("owner");
  }

  @Test
  void restartRestoresConfiguredSelectionRatherThanLastRuntimeChoice() {
    var first = connections();
    first.select("second");
    var restarted = connections();
    assertThat(first.selectedEndpoint()).isEqualTo("second");
    assertThat(restarted.selectedEndpoint()).isEqualTo("first");
  }

  private AgentRunContext owner(String run, String session) {
    return new AgentRunContext(run, session, root, "project");
  }

  private ChatRunConnections connections() {
    var values = new HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "first");
    for (var name : java.util.List.of("first", "second")) {
      var prefix = "rei.llm.features.chat.endpoints." + name + ".";
      values.put(prefix + "base-url", "http://" + name + ".invalid");
      values.put(prefix + "authentication", "none");
      values.put(prefix + "model", name + "-model");
    }
    var props = new Binder(new MapConfigurationPropertySource(values)).bind("rei.llm", LlmProperties.class).get();
    var catalog = new ChatEndpointCatalog(props);
    return new ChatRunConnections(catalog,
        new RunLlmConnectionFactory(catalog, props, ignored -> { throw new AssertionError("No legacy fallback"); }, ignored -> null));
  }
}
