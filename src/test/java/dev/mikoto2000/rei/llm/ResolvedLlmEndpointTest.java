package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Effective configuration and compatibility identity are independent of credential rotation. */
class ResolvedLlmEndpointTest {
  @Test
  void independentEndpointsDoNotInheritTheDefaultCapabilityAndFallbackUsesTheMinimum() {
    var properties = new LlmProperties();
    properties.setMaxTools(256);
    var server = new LlmProperties.Server();
    server.setBaseUrl("https://independent.invalid");
    server.setModel("fixture");
    properties.setFeature("chat", server);
    assertThat(
            new LlmModelProvider(null, properties)
                .resolvedEndpoint("chat")
                .capabilities()
                .maxTools())
        .isNull();
    server.setMaxTools(64);
    properties.setFeature("chat", server);
    assertThat(
            new LlmModelProvider(null, properties)
                .resolvedEndpoint("chat")
                .capabilities()
                .maxTools())
        .isEqualTo(64);
    properties.setMaxTools(32);
    server.setFallbackToDefault(true);
    properties.setFeature("chat", server);
    assertThat(
            new LlmModelProvider(null, properties)
                .resolvedEndpoint("chat")
                .capabilities()
                .maxTools())
        .isEqualTo(32);
    assertThatThrownBy(() -> properties.setMaxTools(0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> server.setMaxTools(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void endpointDiagnosticsDoNotExposeCredentialsEmbeddedInLegacyUrls() {
    var endpoint =
        new ResolvedLlmEndpoint(
            "endpoint",
            1,
            LlmApiType.CHAT_COMPLETIONS,
            "https://user:url-password@endpoint.invalid/v1?api_key=url-secret",
            "credential-reference",
            "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, null),
            1024,
            null,
            java.time.Duration.ofMinutes(5));
    assertThat(endpoint.toString()).doesNotContain("url-password", "url-secret");
  }

  @Test
  void keyRotationPreservesCompatibilityButGenerationChangeDoesNot() {
    var server = new LlmProperties.Server();
    server.setBaseUrl("https://endpoint.invalid/");
    server.setApiKey("first-secret");
    server.setModel("model");
    var first = endpoint(server);
    server.setApiKey("second-secret");
    var rotated = endpoint(server);
    assertThat(first).isEqualTo(rotated);
    assertThat(first.fingerprint()).isEqualTo(rotated.fingerprint());
    assertThat(first.toString()).doesNotContain("first-secret", "second-secret");
    assertThat(first.baseUrl()).isEqualTo("https://endpoint.invalid/v1");
    server.setEndpointGeneration(2);
    assertThat(endpoint(server).fingerprint()).isNotEqualTo(first.fingerprint());
    assertThatThrownBy(() -> server.setEndpointGeneration(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void defaultConnectionAndGenerationOptionsAreResolvedTogether() {
    var defaults = Mockito.mock(ChatModel.class);
    Mockito.when(defaults.getOptions())
        .thenReturn(
            OpenAiChatOptions.builder()
                .baseUrl("https://shared.invalid/v1")
                .apiKey("secret")
                .model("shared-model")
                .temperature(0.4)
                .maxCompletionTokens(1024)
                .build());
    var properties = new LlmProperties();
    properties.setMaxOutputTokens(2048);
    var provider = new LlmModelProvider(defaults, properties);
    var resolved = provider.resolvedEndpoint("chat");
    assertThat(resolved.model()).isEqualTo("shared-model");
    assertThat(resolved.baseUrl()).isEqualTo("https://shared.invalid/v1");
    assertThat(resolved.temperature()).isEqualTo(0.4);
    assertThat(resolved.maxOutputTokens()).isEqualTo(2048);
    assertThat(resolved.capabilities().reasoningReplay()).isEqualTo(ReasoningReplayMode.NONE);
    assertThat(resolved.apiType()).isEqualTo(LlmApiType.CHAT_COMPLETIONS);
    assertThat(provider.chatOptions("chat", null).getMaxCompletionTokens()).isEqualTo(2048);
    assertThat(resolved.toString()).doesNotContain("secret");
  }

  @Test
  void protocolAndReplayModeAreDistinctCompatibilityBoundaries() {
    var legacy =
        new ResolvedLlmEndpoint(
            "endpoint",
            1,
            LlmApiType.CHAT_COMPLETIONS,
            "https://endpoint.invalid/v1",
            "credential",
            "model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 128),
            1024,
            null,
            java.time.Duration.ofMinutes(5));
    var responses =
        new ResolvedLlmEndpoint(
            "endpoint",
            1,
            LlmApiType.RESPONSES,
            legacy.baseUrl(),
            legacy.credentialRef(),
            legacy.model(),
            new LlmCapabilities(ReasoningReplayMode.ENCRYPTED, 128),
            1024,
            null,
            legacy.readTimeout());
    assertThat(responses.fingerprint()).isNotEqualTo(legacy.fingerprint());
    assertThatThrownBy(() -> new ChatCompletionsAdapter().options(responses))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LlmCapabilities(ReasoningReplayMode.NONE, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ResolvedLlmEndpoint endpoint(LlmProperties.Server server) {
    var properties = new LlmProperties();
    properties.setFeature("chat", server);
    return new LlmModelProvider(Mockito.mock(ChatModel.class), properties).resolvedEndpoint("chat");
  }
}
