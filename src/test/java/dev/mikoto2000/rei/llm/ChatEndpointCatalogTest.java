package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointCatalogTest {
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
