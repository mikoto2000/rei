package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointCatalogSnapshotTest {
  @Test
  void connectionCopiesAreCanonicalAndCannotMutateTheCatalog() {
    var properties = properties(settings());
    var catalog = new ChatEndpointCatalog(properties);
    var copy = catalog.connection("local");
    assertThat(copy.getBaseUrl()).isEqualTo("http://localhost:11434/v1");
    assertThat(copy.getModel()).isEqualTo("local-model");
    copy.setModel("changed");
    copy.setApiKey("changed");
    copy.setEndpointGeneration(2);
    var replacement = properties.feature(LlmFeature.CHAT);
    replacement.setEndpoints(Map.of());
    properties.setFeature(LlmFeature.CHAT, replacement);
    assertThat(catalog.connection("local").getModel()).isEqualTo("local-model");
    assertThat(catalog.connection("local").getApiKey()).isEqualTo("PRIVATE_ENDPOINT");
    assertThat(catalog.connection("local").getEndpointGeneration()).isEqualTo(1);
    assertThatThrownBy(() -> catalog.profiles().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void planConnectionsResolveOnlyTheOfficialDestinationWithoutAStoredToken() {
    var values =
        new HashMap<>(
            Map.of(
                "rei.llm.features.chat.active", "subscription",
                "rei.llm.features.chat.endpoints.subscription.provider", "chatgpt-plan",
                "rei.llm.features.chat.endpoints.subscription.api", "responses",
                "rei.llm.features.chat.endpoints.subscription.account-ref", "personal",
                "rei.llm.features.chat.endpoints.subscription.model", "fixture-model"));
    var connection = new ChatEndpointCatalog(properties(values)).connection("subscription");
    assertThat(connection.getBaseUrl()).isEqualTo(dev.mikoto2000.rei.chatgpt.PlanOAuth.RESOURCE);
    assertThat(connection.getApiKey()).isNull();
    assertThat(connection.getAccountRef()).isEqualTo("personal");
    assertThat(connection.getApi()).isEqualTo(LlmApiType.RESPONSES);
  }

  @Test
  void unknownNamesCannotFallBackOrEchoTheInput() {
    var catalog = new ChatEndpointCatalog(properties(settings()));
    assertThatThrownBy(() -> catalog.connection("PRIVATE_CANARY"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint-name")
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  @Test
  void auxiliaryFeatureSettingsRetainTheirOriginalConnectionAndAreFrozen() {
    var values = settings();
    values.put("rei.llm.features.chat.base-url", "http://legacy.invalid:8000");
    values.put("rei.llm.features.chat.model", "legacy-helper-model");
    values.put("rei.llm.features.chat.api-key", "PRIVATE_LEGACY");
    values.put("rei.llm.features.memory.base-url", "http://memory.invalid:8000");
    values.put("rei.llm.features.memory.model", "memory-model");
    var properties = properties(values);
    var catalog = new ChatEndpointCatalog(properties);
    assertThat(catalog.feature(LlmFeature.CHAT).getModel()).isEqualTo("legacy-helper-model");
    assertThat(catalog.feature(LlmFeature.CHAT).getEndpoints()).isNull();
    assertThat(catalog.feature(LlmFeature.MEMORY).getModel()).isEqualTo("memory-model");
    assertThat(catalog.feature(LlmFeature.VOICE_CORRECTION)).isNull();
    var returned = catalog.feature(LlmFeature.MEMORY);
    returned.setModel("mutated");
    properties.setFeature(LlmFeature.MEMORY, returned);
    assertThat(catalog.feature(LlmFeature.MEMORY).getModel()).isEqualTo("memory-model");
    assertThat(catalog.connection("local").getModel()).isEqualTo("local-model");
    assertThat(catalog.diagnostics())
        .anySatisfy(message -> assertThat(message).contains("rei.llm.features.chat.model"));
    assertThat(catalog.diagnostics().toString())
        .doesNotContain("PRIVATE_LEGACY", "PRIVATE_ENDPOINT");
  }

  @Test
  void externalYamlProfilesMergeWithLegacyDefaultsAndResolveEnvironmentPlaceholders()
      throws Exception {
    var loader = new org.springframework.boot.env.YamlPropertySourceLoader();
    var defaults =
        loader.load(
            "defaults",
            resource(
                """
                rei:
                  llm:
                    features:
                      chat:
                        base-url: http://legacy.invalid:8000
                        model: legacy-helper-model
                        api-key: ${LEGACY_KEY}
                """));
    var external =
        loader.load(
            "external",
            resource(
                """
                rei:
                  llm:
                    features:
                      chat:
                        active: local
                        endpoints:
                          local:
                            base-url: http://localhost:11434
                            model: ${ENDPOINT_MODEL:local-model}
                            api-key: ${ENDPOINT_KEY}
                """));
    var environment =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("LEGACY_KEY", "PRIVATE_LEGACY")
            .withProperty("ENDPOINT_KEY", "PRIVATE_ENDPOINT");
    defaults.forEach(source -> environment.getPropertySources().addLast(source));
    external.forEach(source -> environment.getPropertySources().addFirst(source));
    var properties = Binder.get(environment).bind("rei.llm", LlmProperties.class).get();
    var catalog = new ChatEndpointCatalog(properties);
    assertThat(catalog.initialEndpoint()).isEqualTo("local");
    assertThat(catalog.connection("local").getModel()).isEqualTo("local-model");
    assertThat(catalog.connection("local").getApiKey()).isEqualTo("PRIVATE_ENDPOINT");
    assertThat(catalog.feature(LlmFeature.CHAT).getModel()).isEqualTo("legacy-helper-model");
    assertThat(catalog.feature(LlmFeature.CHAT).getApiKey()).isEqualTo("PRIVATE_LEGACY");
    var output =
        new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(catalog.profiles());
    assertThat(output).doesNotContain("PRIVATE_ENDPOINT", "PRIVATE_LEGACY");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"memory,provider", "memory,account-ref"})
  void auxiliaryInferenceCannotSilentlyAcquirePlanAuthentication(String feature, String field) {
    var values = settings();
    values.put(
        "rei.llm.features." + feature + "." + field,
        field.equals("provider") ? "chatgpt-plan" : "personal");
    assertThatThrownBy(() -> new ChatEndpointCatalog(properties(values)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features." + feature + "." + field);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"model", "api-key"})
  void modelAndCredentialControlCharactersAreDiagnosedWithoutEchoingValues(String field) {
    var values = settings();
    values.put("rei.llm.features.chat.endpoints.local." + field, "PRIVATE_CANARY\nvalue");
    assertThatThrownBy(() -> new ChatEndpointCatalog(properties(values)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.chat.endpoints.local." + field)
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  private org.springframework.core.io.ByteArrayResource resource(String yaml) {
    return new org.springframework.core.io.ByteArrayResource(
        yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static HashMap<String, String> settings() {
    return new HashMap<>(
        Map.of(
            "rei.llm.features.chat.active", "local",
            "rei.llm.features.chat.endpoints.local.base-url", "http://localhost:11434/",
            "rei.llm.features.chat.endpoints.local.model", "local-model",
            "rei.llm.features.chat.endpoints.local.api-key", "PRIVATE_ENDPOINT"));
  }

  private LlmProperties properties(Map<String, String> settings) {
    return new Binder(new MapConfigurationPropertySource(settings))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }
}
