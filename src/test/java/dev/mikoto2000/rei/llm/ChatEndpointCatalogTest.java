package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointCatalogTest {
  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.MethodSource("invalidSettings")
  void invalidConfigurationNamesTheFieldWithoutEchoingValues(
      String scenario, Map<String, String> settings, String field) {
    assertThatThrownBy(() -> catalog(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field)
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidSettings() {
    String prefix = "rei.llm.features.chat.";
    var mutations = java.util.List.of(
        new String[] {"unknown-active", "active", "missing", "active"},
        new String[] {"blank-active", "active", "", "active"},
        new String[] {"blank-model", "endpoints.local.model", "", "endpoints.local.model"},
        new String[] {"blank-url", "endpoints.local.base-url", "", "endpoints.local.base-url"},
        new String[] {"url-scheme", "endpoints.local.base-url", "ftp://fixture.invalid", "endpoints.local.base-url"},
        new String[] {"url-userinfo", "endpoints.local.base-url", "http://user:PRIVATE_CANARY@fixture.invalid", "endpoints.local.base-url"},
        new String[] {"url-query", "endpoints.local.base-url", "http://fixture.invalid?key=PRIVATE_CANARY", "endpoints.local.base-url"},
        new String[] {"url-fragment", "endpoints.local.base-url", "http://fixture.invalid/#PRIVATE_CANARY", "endpoints.local.base-url"},
        new String[] {"url-port", "endpoints.local.base-url", "http://fixture.invalid:99999", "endpoints.local.base-url"},
        new String[] {"nested-active", "endpoints.local.active", "child", "endpoints.local.active"},
        new String[] {"fallback", "endpoints.local.fallback-to-default", "true", "endpoints.local.fallback-to-default"});
    return mutations.stream().map(mutation -> {
      var settings = validSettings();
      settings.put(prefix + mutation[1], mutation[2]);
      return org.junit.jupiter.params.provider.Arguments.of(mutation[0], settings, prefix + mutation[3]);
    });
  }

  @Test
  void activeWithoutEndpointsIsDiagnosed() {
    assertThatThrownBy(() -> catalog(Map.of("rei.llm.features.chat.active", "missing")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.chat.endpoints");
  }

  @Test
  void anExplicitEmptyCatalogIsNotSilentlyTreatedAsLegacy() {
    var properties = new LlmProperties();
    var chat = new LlmProperties.Server();
    chat.setActive("local");
    chat.setEndpoints(Map.of());
    properties.setFeature(LlmFeature.CHAT, chat);
    assertThatThrownBy(() -> new ChatEndpointCatalog(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.chat.endpoints");
  }

  @Test
  void endpointNamesAreValidatedWithoutEchoingAnInvalidName() {
    var properties = new LlmProperties();
    var chat = new LlmProperties.Server();
    var connection = new LlmProperties.Server();
    connection.setModel("fixture-model");
    connection.setBaseUrl("http://localhost:11434");
    chat.setActive("PRIVATE_CANARY name");
    chat.setEndpoints(Map.of("PRIVATE_CANARY name", connection));
    properties.setFeature(LlmFeature.CHAT, chat);
    assertThatThrownBy(() -> new ChatEndpointCatalog(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.chat.endpoints")
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  @Test
  void otherFeaturesCannotAccidentallyAcquireDynamicChatSelection() {
    assertThatThrownBy(() -> catalog(Map.of(
        "rei.llm.features.memory.active", "local", "rei.llm.features.memory.model", "memory-model")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.memory.active");
  }

  @Test
  void startupRejectsAnUnknownActiveProfile() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(CatalogConfiguration.class)
        .withPropertyValues("rei.llm.features.chat.active=missing",
            "rei.llm.features.chat.endpoints.local.base-url=http://localhost:11434",
            "rei.llm.features.chat.endpoints.local.model=local-model")
        .run(context -> assertThat(context).hasFailed());
  }

  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  @org.springframework.boot.context.properties.EnableConfigurationProperties(LlmProperties.class)
  @org.springframework.context.annotation.Import(ChatEndpointCatalog.class)
  static class CatalogConfiguration {}

  private static java.util.HashMap<String, String> validSettings() {
    return new java.util.HashMap<>(Map.of(
        "rei.llm.features.chat.active", "local",
        "rei.llm.features.chat.endpoints.local.base-url", "http://localhost:11434",
        "rei.llm.features.chat.endpoints.local.model", "local-model"));
  }

  @Test
  void legacyConnectionRemainsASingleDefaultProfile() {
    var catalog = catalog(Map.of(
        "rei.llm.features.chat.base-url", "http://localhost:11434",
        "rei.llm.features.chat.api-key", "fixture-secret",
        "rei.llm.features.chat.model", "legacy-model"));
    assertThat(catalog.initialEndpoint()).isEqualTo("default");
    assertThat(catalog.profiles()).singleElement().satisfies(profile -> {
      assertThat(profile.id()).isEqualTo("default");
      assertThat(profile.model()).isEqualTo("legacy-model");
    });
    assertThat(catalog.profiles().toString()).doesNotContain("fixture-secret");
  }

  @Test
  void absentChatStillUsesTheSharedConnection() {
    var catalog = new ChatEndpointCatalog(new LlmProperties());
    assertThat(catalog.initialEndpoint()).isEqualTo("default");
    assertThat(catalog.profiles()).singleElement().satisfies(profile -> {
      assertThat(profile.id()).isEqualTo("default");
      assertThat(profile.model()).isNull();
    });
  }

  @Test
  void namedProfilesAreRegisteredSeparatelyInStableOrder() {
    var catalog = catalog(Map.of(
        "rei.llm.features.chat.active", "remote",
        "rei.llm.features.chat.endpoints.remote.base-url", "http://fixture.invalid:8000",
        "rei.llm.features.chat.endpoints.remote.model", "remote-model",
        "rei.llm.features.chat.endpoints.local.base-url", "http://localhost:11434",
        "rei.llm.features.chat.endpoints.local.model", "local-model"));
    assertThat(catalog.profiles()).extracting(ChatEndpointCatalog.Profile::id)
        .containsExactly("local", "remote");
    assertThat(catalog.profiles()).extracting(ChatEndpointCatalog.Profile::model)
        .containsExactly("local-model", "remote-model");
    assertThat(catalog.initialEndpoint()).isEqualTo("remote");
  }

  private ChatEndpointCatalog catalog(Map<String, String> settings) {
    var properties = new Binder(new MapConfigurationPropertySource(settings))
        .bind("rei.llm", LlmProperties.class).orElseGet(LlmProperties::new);
    return new ChatEndpointCatalog(properties);
  }
}
