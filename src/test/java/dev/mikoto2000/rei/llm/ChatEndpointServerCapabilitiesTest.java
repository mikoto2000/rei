package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointServerCapabilitiesTest {
  private static final String PATH = "rei.llm.features.chat.endpoints.local.";

  @Test
  void apiSelectionAndModelDeclarationsDoNotConfirmServerCompatibility() {
    var settings = settings();
    for (var capability : java.util.List.of("responses", "streaming", "function-calling", "vision")) {
      settings.put(PATH + "model-capabilities." + capability, "supported");
      var name = capability.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
      assertThat(view(settings).at("/capabilities/server/" + name).asText()).isEqualTo("UNKNOWN");
      assertThat(view(settings).at("/capabilities/effective/" + name).asText()).isEqualTo("UNKNOWN");
    }
  }

  @ParameterizedTest
  @CsvSource({"supported,supported,SUPPORTED", "unsupported,supported,UNSUPPORTED",
      "supported,unsupported,UNSUPPORTED", "unknown,supported,UNKNOWN", "supported,unknown,UNKNOWN"})
  void serverAndModelDeclarationsIntersectWithTheAdapter(
      String server, String model, String effective) {
    var settings = settings();
    settings.put(PATH + "server-capabilities.function-calling", server);
    settings.put(PATH + "model-capabilities.function-calling", model);
    assertThat(view(settings).at("/capabilities/effective/FUNCTION_CALLING").asText()).isEqualTo(effective);
    settings.put(PATH + "server-capabilities.hosted-mcp", "supported");
    settings.put(PATH + "model-capabilities.hosted-mcp", "supported");
    assertThat(view(settings).at("/capabilities/effective/HOSTED_MCP").asText()).isEqualTo("UNSUPPORTED");
  }

  @Test
  void selectingReplayDoesNotProveTheServersReplayContract() {
    var settings = settings();
    settings.put(PATH + "reasoning-replay", "plaintext");
    settings.put(PATH + "model-capabilities.reasoning-replay", "supported");
    assertThat(view(settings).at("/capabilities/effective/REASONING_REPLAY").asText()).isEqualTo("UNKNOWN");
    settings.put(PATH + "server-capabilities.reasoning-replay", "supported");
    assertThat(view(settings).at("/capabilities/effective/REASONING_REPLAY").asText()).isEqualTo("SUPPORTED");
    settings.put(PATH + "reasoning-replay", "none");
    assertThat(view(settings).at("/capabilities/effective/REASONING_REPLAY").asText()).isEqualTo("UNSUPPORTED");
  }

  @Test
  void serverReplaySupportDoesNotProveModelPayloadCompatibility() {
    var settings = settings();
    settings.put(PATH + "reasoning-replay", "encrypted");
    settings.put(PATH + "server-capabilities.reasoning-replay", "supported");
    assertThat(view(settings).at("/capabilities/effective/REASONING_REPLAY").asText()).isEqualTo("UNKNOWN");
  }

  @ParameterizedTest
  @CsvSource({"vision,PRIVATE_CANARY", "PRIVATE_CANARY,supported"})
  void invalidServerDeclarationsHaveSafeFieldDiagnostics(String key, String value) {
    var settings = settings();
    settings.put(PATH + "server-capabilities." + key, value);
    assertThatThrownBy(() -> catalog(settings)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(PATH + "server-capabilities").hasMessageNotContaining("PRIVATE_CANARY");
  }

  private HashMap<String, String> settings() {
    return new HashMap<>(Map.of("rei.llm.features.chat.active", "local",
        PATH + "base-url", "http://localhost:8000", PATH + "model", "fixture-model",
        PATH + "api", "responses", PATH + "authentication", "none"));
  }

  private ChatEndpointCatalog catalog(Map<String, String> settings) {
    return new ChatEndpointCatalog(new Binder(new MapConfigurationPropertySource(settings))
        .bind("rei.llm", LlmProperties.class).get());
  }

  private JsonNode view(Map<String, String> settings) {
    return new ObjectMapper().valueToTree(catalog(settings).profiles().getFirst());
  }
}
