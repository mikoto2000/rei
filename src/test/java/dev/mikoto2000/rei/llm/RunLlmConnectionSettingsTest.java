package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunLlmConnectionSettingsTest {
  @Test
  void nestedSettingsAreCopiedBeforeTheCallerCanChangeThem() {
    var format = new HashMap<String, Object>();
    format.put("type", "json_object");
    var snapshot = connection(Map.of("response_format", format));
    format.put("type", "text");
    assertThat(snapshot.generation().get("response_format"))
        .isEqualTo(Map.of("type", "json_object"));
  }

  @Test
  void restoredNestedSettingsCannotBeMutated() throws Exception {
    var snapshot = connection(Map.of("response_format", Map.of("type", "json_object")));
    var json = new ObjectMapper().findAndRegisterModules();
    var restored = json.readValue(json.writeValueAsBytes(snapshot), RunLlmConnection.class);
    assertThat(restored).isEqualTo(snapshot);
    assertThatThrownBy(() -> ((Map<?, ?>) restored.generation().get("response_format")).clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"apiKey", "accessToken", "credential", "toolContext", "customHeaders", "headers", "base_url"})
  void generationCannotBecomeAnAlternativeCredentialOrConnectionContainer(String field) {
    assertThatThrownBy(() -> connection(Map.of(field, "PRIVATE_CANARY")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  @Test
  void settingsCannotHoldOpaqueRuntimeObjects() {
    assertThatThrownBy(() -> connection(Map.of("response_format", new Object())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private RunLlmConnection connection(Map<String, Object> settings) {
    var endpoint = new ResolvedLlmEndpoint("local", 1, LlmApiType.CHAT_COMPLETIONS,
        "http://local.invalid/v1", "local", "model",
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), 512, null,
        Duration.ofSeconds(30));
    return new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null, endpoint, Map.of(), settings);
  }
}
