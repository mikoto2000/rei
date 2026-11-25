package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RunLlmConnectionFactoryTest {
  @Test
  void modelOverrideDoesNotBorrowCapabilitiesFromTheConfiguredModel() {
    var settings = local();
    var prefix = "rei.llm.features.chat.endpoints.local.";
    settings.remove(prefix + "temperature");
    settings.remove(prefix + "max-output-tokens");
    settings.put(prefix + "model-capabilities.vision", "supported");
    settings.put(prefix + "server-capabilities.vision", "supported");
    var factory = factory(properties(settings));
    assertThat(factory.capture("local", null).capabilities().get(LlmEndpointCapabilities.Capability.VISION))
        .isEqualTo(LlmEndpointCapabilities.Support.SUPPORTED);
    var other = factory.capture("local", "other-model");
    assertThat(other.endpoint().model()).isEqualTo("other-model");
    assertThat(other.capabilities().get(LlmEndpointCapabilities.Capability.VISION))
        .isEqualTo(LlmEndpointCapabilities.Support.UNKNOWN);
    assertThat(other.endpoint().capabilities().supportedSettings()).doesNotContain(LlmCapabilities.Setting.IMAGE_INPUT);
  }

  @Test
  void explicitUnsupportedTemperatureIsRejectedBeforeAnyClientCanBeConstructed() {
    var settings = local();
    settings.put("rei.llm.features.chat.endpoints.local.model-capabilities.temperature", "unsupported");
    assertThatThrownBy(() -> factory(properties(settings)).capture("local", null))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("temperature");
  }

  @Test
  void catalogGenerationAlsoFreezesInheritedDefaultsBeforeRunCapture() {
    var settings = local();
    settings.remove("rei.llm.features.chat.endpoints.local.max-output-tokens");
    var properties = properties(settings);
    properties.setReadTimeout(Duration.ofSeconds(42));
    var factory = factory(properties);
    properties.setMaxOutputTokens(1000);
    properties.setReadTimeout(Duration.ofSeconds(9));
    var snapshot = factory.capture("local", null);
    assertThat(snapshot.endpoint().readTimeout()).isEqualTo(Duration.ofSeconds(42));
    assertThat(snapshot.endpoint().maxOutputTokens()).isEqualTo(8192);
  }

  @Test
  void durableSnapshotRoundTripsWithoutBecomingMutable() throws Exception {
    var snapshot = factory(properties(local())).capture("local", null);
    var json = new ObjectMapper().findAndRegisterModules();
    var restored = json.readValue(json.writeValueAsBytes(snapshot), RunLlmConnection.class);
    assertThat(restored).isEqualTo(snapshot);
    assertThatThrownBy(() -> restored.generation().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void namedSnapshotFreezesSettingsAndNeverIncludesLocalOrGlobalCredentials() throws Exception {
    var settings = local();
    settings.put("rei.llm.features.chat.api-key", "PRIVATE_GLOBAL_CANARY");
    settings.put("rei.llm.features.chat.endpoints.local.authentication", "api-key");
    settings.put("rei.llm.features.chat.endpoints.local.api-key", "PRIVATE_LOCAL_CANARY");
    var properties = properties(settings);
    properties.setReadTimeout(Duration.ofSeconds(42));
    var snapshot = factory(properties).capture("local", null);
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.profileId()).isEqualTo("local");
    assertThat(snapshot.named()).isTrue();
    assertThat(snapshot.authentication()).isEqualTo(LlmAuthentication.API_KEY);
    assertThat(snapshot.endpoint().model()).isEqualTo("fixture-model");
    assertThat(snapshot.endpoint().baseUrl()).isEqualTo("http://localhost:8000/v1");
    assertThat(snapshot.endpoint().readTimeout()).isEqualTo(Duration.ofSeconds(42));
    assertThat(snapshot.generation())
        .containsEntry("max_tokens", 2048)
        .containsEntry("temperature", 0.5);
    properties.setReadTimeout(Duration.ofSeconds(9));
    properties.setFeature("chat", new LlmProperties.Server());
    assertThat(snapshot.endpoint().readTimeout()).isEqualTo(Duration.ofSeconds(42));
    var json = new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);
    assertThat(json)
        .doesNotContain(
            "PRIVATE_GLOBAL_CANARY",
            "PRIVATE_LOCAL_CANARY",
            "apiKey",
            "accessToken",
            "refreshToken");
    assertThat(snapshot.toString()).doesNotContain("localhost", "fixture-model", "PRIVATE");
    assertThatThrownBy(() -> snapshot.generation().put("temperature", 1.0))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> snapshot.capabilities().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aNamedProfileCalledDefaultDoesNotBecomeTheLegacyConnection() {
    var settings = new HashMap<String, String>();
    local().forEach((key, value) -> settings.put(key.replace(".local.", ".default."), value));
    settings.put("rei.llm.features.chat.active", "default");
    var snapshot = factory(properties(settings)).capture("default", null);
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.named()).isTrue();
    assertThat(snapshot.endpoint().model()).isEqualTo("fixture-model");
  }

  @Test
  void flatConfigurationUsesItsLegacyResolutionWithoutGuessingAServerOrModel() {
    var snapshot = factory(new LlmProperties()).capture("default", null);
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.named()).isFalse();
    assertThat(snapshot.endpoint()).isEqualTo(legacy());
    assertThat(snapshot.authentication()).isEqualTo(LlmAuthentication.LEGACY);
  }

  @Test
  void planSnapshotPinsRegistrationAndReplayWithoutInheritingGenericGenerationDefaults() {
    var props =
        properties(
            Map.of(
                "rei.llm.features.chat.active", "subscription",
                "rei.llm.features.chat.endpoints.subscription.provider", "chatgpt-plan",
                "rei.llm.features.chat.endpoints.subscription.api", "responses",
                "rei.llm.features.chat.endpoints.subscription.account-ref", "personal",
                "rei.llm.features.chat.endpoints.subscription.model", "fixture-plan"));
    var snapshot = factory(props).capture("subscription", null);
    assertThat(snapshot).isNotNull();
    assertThat(snapshot.accountRegistration()).isEqualTo("registration-1");
    assertThat(snapshot.endpoint().capabilities().reasoningReplay())
        .isEqualTo(ReasoningReplayMode.ENCRYPTED);
    assertThat(snapshot.endpoint().maxOutputTokens()).isNull();
    assertThat(snapshot.generation()).isEmpty();
  }

  private RunLlmConnectionFactory factory(LlmProperties props) {
    return new RunLlmConnectionFactory(
        new ChatEndpointCatalog(props), props, ignored -> legacy(), ignored -> "registration-1");
  }

  private static ResolvedLlmEndpoint legacy() {
    return new ResolvedLlmEndpoint(
        "default",
        1,
        LlmApiType.CHAT_COMPLETIONS,
        "http://legacy.invalid/v1",
        "default",
        "legacy-model",
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()),
        8192,
        0.3,
        Duration.ofSeconds(120));
  }

  private LlmProperties properties(Map<String, String> values) {
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }

  private HashMap<String, String> local() {
    var settings = new HashMap<String, String>();
    settings.put("rei.llm.features.chat.active", "local");
    var prefix = "rei.llm.features.chat.endpoints.local.";
    settings.put(prefix + "base-url", "http://localhost:8000");
    settings.put(prefix + "model", "fixture-model");
    settings.put(prefix + "authentication", "none");
    settings.put(prefix + "max-output-tokens", "2048");
    settings.put(prefix + "temperature", "0.5");
    for (var name : java.util.List.of("max-output-tokens", "temperature")) {
      settings.put(prefix + "server-capabilities." + name, "supported");
      settings.put(prefix + "model-capabilities." + name, "supported");
    }
    return settings;
  }
}
