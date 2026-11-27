package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointModelDeclarationsTest {
  private static final String PREFIX = "rei.llm.features.chat.endpoints.fixture.";

  @ParameterizedTest
  @CsvSource({
    "openai-compatible,chat-completions,reasoning-replay,supported,UNSUPPORTED",
    "chatgpt-plan,responses,temperature,supported,UNSUPPORTED",
    "openai-compatible,responses,vision,unsupported,UNSUPPORTED",
    "openai-compatible,responses,vision,unknown,UNKNOWN",
    "openai-compatible,responses,vision,supported,SUPPORTED"
  })
  void modelConfirmationCannotOverrideTheRouteOrServer(
      String provider,
      String api,
      String capability,
      String server,
      LlmEndpointCapabilities.Support expected) {
    var settings = settings(provider, api);
    settings.put(PREFIX + "server-capabilities." + capability, server);
    settings.put(PREFIX + "model-capabilities-by-name.[second-model]." + capability, "supported");
    var catalog = new ChatEndpointCatalog(properties(settings));
    var selected = catalog.capabilities(catalog.profiles().getFirst(), "second-model");
    assertThat(selected.effective())
        .containsEntry(
            LlmEndpointCapabilities.Capability.valueOf(
                capability.toUpperCase(java.util.Locale.ROOT).replace('-', '_')),
            expected);
  }

  @Test
  void declarationsAreDeeplyFrozenAcrossInputGettersAndConfigurationCopies() {
    var entry = new HashMap<>(Map.of("vision", "supported"));
    var input = new HashMap<String, Map<String, String>>();
    input.put("model.with/slash", entry);
    var server = new LlmProperties.Server();
    server.setModelCapabilitiesByName(input);
    entry.put("vision", "unsupported");
    input.clear();
    assertThat(server.copy().getModelCapabilitiesByName().get("model.with/slash"))
        .containsEntry("vision", "supported");
    assertThatThrownBy(() -> server.getModelCapabilitiesByName().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> server.getModelCapabilitiesByName().get("model.with/slash").clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @ParameterizedTest
  @CsvSource({"not-a-capability,supported", "vision,PRIVATE_VALUE"})
  void invalidDeclarationsFailBeforeAnyModelSelection(String capability, String value) {
    var settings = settings("openai-compatible", "responses");
    settings.put(PREFIX + "model-capabilities-by-name.[second-model]." + capability, value);
    assertThatThrownBy(() -> new ChatEndpointCatalog(properties(settings)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model-capabilities-by-name")
        .hasMessageNotContaining("PRIVATE_VALUE");
  }

  @Test
  void duplicateCapabilityAliasesAreRejectedBeforeSelection() {
    var server = new LlmProperties.Server();
    server.setBaseUrl("http://fixture.invalid/v1");
    server.setAuthentication("none");
    server.setModel("first-model");
    server.setModelCapabilitiesByName(
        Map.of("second-model", Map.of("vision", "supported", "VISION", "supported")));
    var chat = new LlmProperties.Server();
    chat.setActive("fixture");
    chat.setEndpoints(Map.of("fixture", server));
    var properties = new LlmProperties();
    properties.setFeature(LlmFeature.CHAT, chat);
    assertThatThrownBy(() -> new ChatEndpointCatalog(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate capability");
  }

  @ParameterizedTest
  @ValueSource(strings = {"chat", "summary"})
  void perModelDeclarationsCannotBeSilentlyPlacedOnFlatOrHelperSettings(String feature) {
    var settings = settings("openai-compatible", "responses");
    String field = "rei.llm.features." + feature + ".model-capabilities-by-name";
    settings.put(field + ".[second-model].vision", "supported");
    assertThatThrownBy(() -> new ChatEndpointCatalog(properties(settings)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field);
  }

  @Test
  void generationMetadataForADeclaredPlanModelNeedsNoAccountOrCredentialLookup() {
    var settings = settings("chatgpt-plan", "responses");
    settings.put(PREFIX + "server-capabilities.reasoning-effort", "supported");
    settings.put(
        PREFIX + "model-capabilities-by-name.[second-model].reasoning-effort", "supported");
    settings.put(PREFIX + "generation.reasoning-effort", "low");
    var properties = properties(settings);
    var factory =
        new RunLlmConnectionFactory(
            new ChatEndpointCatalog(properties),
            properties,
            ignored -> {
              throw new AssertionError("No legacy access");
            },
            ignored -> {
              throw new AssertionError("No account access");
            });
    assertThat(factory.configuredGeneration("fixture", "second-model"))
        .containsEntry("reasoning_effort", "low");
  }

  private HashMap<String, String> settings(String provider, String api) {
    var values = new HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "fixture");
    values.put(PREFIX + "provider", provider);
    values.put(PREFIX + "api", api);
    values.put(PREFIX + "model", "first-model");
    if (provider.equals("chatgpt-plan")) values.put(PREFIX + "account-ref", "personal");
    else {
      values.put(PREFIX + "base-url", "http://owned-fixture.invalid/v1");
      values.put(PREFIX + "authentication", "none");
      if (api.equals("responses")) values.put(PREFIX + "reasoning-replay", "none");
    }
    return values;
  }

  private LlmProperties properties(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }
}
