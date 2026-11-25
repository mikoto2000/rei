package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Explicit production opt-in uses only the effective configuration accepted by real trials. */
class ResponsesProductionGateTest {
  static final String PROFILE = "openai-gpt-5.6-sol-low-20261125";

  static LlmProperties properties(String feature, Map<String, String> changes) {
    var values = new LinkedHashMap<String, Object>();
    values.put("rei.llm.read-timeout", changes.getOrDefault("timeout", "10m"));
    String prefix = "rei.llm.features." + feature + ".";
    var server = new LinkedHashMap<String, String>();
    server.put("api", "RESPONSES");
    server.put("base-url", "https://api.openai.com");
    server.put("api-key", "owned-fixture-not-a-real-key");
    server.put("model", "gpt-5.6-sol");
    server.put("endpoint-generation", "1");
    server.put("reasoning-replay", "ENCRYPTED");
    server.put("max-output-tokens", "2048");
    server.put("max-tools", "36");
    server.put("supported-settings", "REASONING_EFFORT");
    server.put("accepted-responses-profile", PROFILE);
    changes.forEach((key, value) -> {
      if (!key.equals("timeout")) server.put(key, value);
    });
    server.forEach((key, value) -> values.put(prefix + key, value));
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }

  @ParameterizedTest
  @ValueSource(strings = {"chat", "output-limit-planner"})
  void onlyTheAcceptedOpenAiFeaturesCanConstructTheNativeModelWithoutAnAcceptanceFactory(
      String feature) {
    var properties = properties(feature, Map.of());
    var provider = new LlmModelProvider(org.mockito.Mockito.mock(ChatModel.class), properties);
    assertThatCode(() -> provider.chatModel(feature)).doesNotThrowAnyException();
    var endpoint = provider.resolvedEndpoint(feature);
    assertThat(endpoint.readTimeout()).isEqualTo(Duration.ofSeconds(120));
    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofMinutes(10));
    assertThat(endpoint.model()).isEqualTo("gpt-5.6-sol");
    assertThat(endpoint.baseUrl()).isEqualTo("https://api.openai.com/v1");
    assertThat(endpoint.maxOutputTokens()).isEqualTo(2048);
    assertThat(endpoint.capabilities().maxTools()).isEqualTo(36);
    var options = (OpenAiResponsesChatOptions) provider.chatModel(feature).getOptions();
    assertThat(options.getReasoningEffort()).isEqualTo("low");
    assertThat(options.getServiceTier()).isEqualTo("default");
    assertThat(options.getMaxRetries()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"absent-profile", "profile", "base", "model", "generation", "output",
      "tools", "replay", "settings", "temperature", "timeout", "fallback", "key", "feature", "api"})
  void unverifiedEffectiveSettingsAndFailedLocalAcceptanceRemainClosed(String change) {
    String feature = change.equals("feature") ? LlmFeature.MEMORY : LlmFeature.CHAT;
    Map<String, String> changes = switch (change) {
      case "absent-profile" -> Map.of("accepted-responses-profile", "");
      case "profile" -> Map.of("accepted-responses-profile", "local-deepseek-unverified");
      case "base" -> Map.of("base-url", "http://gx10-707e.local:8888");
      case "model" -> Map.of("model", "deepseek-v4.1-flash-1017");
      case "generation" -> Map.of("endpoint-generation", "2");
      case "output" -> Map.of("max-output-tokens", "1024");
      case "tools" -> Map.of("max-tools", "35");
      case "replay" -> Map.of("reasoning-replay", "NONE");
      case "settings" -> Map.of("supported-settings", "REASONING_EFFORT,IMAGE_INPUT");
      case "temperature" -> Map.of("temperature", "0.0");
      case "timeout" -> Map.of("timeout", "30s");
      case "fallback" -> Map.of("fallback-to-default", "true");
      case "key" -> Map.of("api-key", "dummy-key");
      case "api" -> Map.of("api", "CHAT_COMPLETIONS");
      default -> Map.of();
    };
    var provider = new LlmModelProvider(
        org.mockito.Mockito.mock(ChatModel.class), properties(feature, changes));
    assertThatThrownBy(() -> provider.resolvedEndpoint(feature))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> provider.chatModel(feature))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void defaultChatCompletionsKeepsTheExistingConnectionAndGlobalTimeout() {
    var defaults = org.mockito.Mockito.mock(ChatModel.class);
    var properties = new LlmProperties();
    properties.setReadTimeout(Duration.ofMinutes(10));
    var provider = new LlmModelProvider(defaults, properties);
    assertThat(provider.chatModel(LlmFeature.CHAT)).isSameAs(defaults);
    assertThat(provider.resolvedEndpoint(LlmFeature.CHAT).apiType())
        .isEqualTo(LlmApiType.CHAT_COMPLETIONS);
    assertThat(provider.resolvedEndpoint(LlmFeature.CHAT).readTimeout())
        .isEqualTo(Duration.ofMinutes(10));
  }
}
