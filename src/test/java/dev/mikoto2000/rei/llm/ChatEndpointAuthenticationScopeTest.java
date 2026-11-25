package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointAuthenticationScopeTest {
  @ParameterizedTest
  @CsvSource({"chat,none", "chat,api-key", "voice-correction,none", "summary,chatgpt-oauth"})
  void authenticationModeCannotBeSilentlyIgnoredByLegacyStaticRouting(String feature, String mode) {
    var field = "rei.llm.features." + feature + ".authentication";
    var settings = new HashMap<>(Map.of(field, mode));
    assertThatThrownBy(() -> catalog(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(field)
        .hasMessageContaining("named chat");
  }

  @ParameterizedTest
  @ValueSource(strings = {"chat", "voice-correction", "summary"})
  void explicitOpenAiContractIsAlsoValidatedForStaticFeatureConfigurations(String feature) {
    var prefix = "rei.llm.features." + feature + ".";
    var settings =
        Map.of(
            prefix + "provider",
            "openai-api",
            prefix + "api-key",
            "PRIVATE_CANARY",
            prefix + "base-url",
            "http://localhost:8000",
            prefix + "model",
            "fixture-model");
    assertThatThrownBy(() -> catalog(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(prefix + "base-url")
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  private ChatEndpointCatalog catalog(Map<String, String> settings) {
    return new ChatEndpointCatalog(
        new Binder(new MapConfigurationPropertySource(settings))
            .bind("rei.llm", LlmProperties.class)
            .get());
  }
}
