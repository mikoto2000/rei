package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

class ResponsesAcceptanceProviderTest {
  private final ChatModel defaults = org.mockito.Mockito.mock(ChatModel.class);
  private final List<OpenAiHttpClientBuilderCustomizer> isolated = List.of(builder -> {});

  private LlmProperties properties() {
    var properties = new LlmProperties();
    var server = new LlmProperties.Server();
    server.setApi(LlmApiType.RESPONSES);
    server.setBaseUrl("https://api.openai.com/v1");
    server.setApiKey("owned-fixture-not-a-real-key");
    server.setModel("acceptance-fixture-model");
    server.setReasoningReplay(ReasoningReplayMode.ENCRYPTED);
    server.setMaxOutputTokens(512);
    properties.setFeature(LlmFeature.CHAT, server);
    properties.setFeature(LlmFeature.MEMORY, server);
    return properties;
  }

  private ResolvedLlmEndpoint target(LlmProperties properties) {
    return LlmModelProvider.forResponsesFixture(defaults, properties, isolated)
        .resolvedEndpoint(LlmFeature.CHAT);
  }

  @Test
  void exactExplicitSnapshotCanConstructTheNativeModelWithoutOpeningProduction() {
    var properties = properties();
    var target = target(properties);
    var provider = LlmModelProvider.forResponsesAcceptance(defaults, properties, isolated,
        Map.of(LlmFeature.CHAT, target));
    assertThatCode(() -> {
      assertThat(provider.chatModel(LlmFeature.CHAT)).isInstanceOf(ResponsesAdapter.class);
      assertThat(provider.resolvedEndpoint(LlmFeature.CHAT)).isEqualTo(target);
    }).doesNotThrowAnyException();
    assertThatThrownBy(() -> new LlmModelProvider(defaults, properties).chatModel(LlmFeature.CHAT))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> LlmModelProvider.forResponsesFixture(defaults, properties, isolated)
        .chatModel(LlmFeature.CHAT)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anUnacceptedFeatureCannotBorrowTheOtherFeaturesApproval() {
    var properties = properties();
    var provider = LlmModelProvider.forResponsesAcceptance(defaults, properties, isolated,
        Map.of(LlmFeature.CHAT, target(properties)));
    assertThatThrownBy(() -> provider.chatModel(LlmFeature.MEMORY))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("acceptance endpoint");
  }

  @Test
  void targetMapIsFrozenBeforeAnyModelIsConstructed() {
    var properties = properties();
    var approved = new java.util.HashMap<String, ResolvedLlmEndpoint>();
    approved.put(LlmFeature.CHAT, target(properties));
    var provider = LlmModelProvider.forResponsesAcceptance(defaults, properties, isolated, approved);
    approved.clear();
    assertThatCode(() -> provider.chatModel(LlmFeature.CHAT)).doesNotThrowAnyException();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"model", "generation", "output", "timeout"})
  void anApprovedSnapshotCannotAuthorizeDifferentEffectiveSettings(String field) {
    var properties = properties();
    var original = target(properties);
    var changed = new ResolvedLlmEndpoint(original.endpointId(),
        field.equals("generation") ? original.generation() + 1 : original.generation(),
        original.apiType(), original.baseUrl(), original.credentialRef(),
        field.equals("model") ? "different-model" : original.model(), original.capabilities(),
        field.equals("output") ? 1024 : original.maxOutputTokens(), original.temperature(),
        field.equals("timeout") ? java.time.Duration.ofSeconds(1) : original.readTimeout());
    var provider = LlmModelProvider.forResponsesAcceptance(defaults, properties, isolated,
        Map.of(LlmFeature.CHAT, changed));
    assertThatThrownBy(() -> provider.chatModel(LlmFeature.CHAT))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("acceptance endpoint");
  }

  @Test
  void noAcceptanceOrTransportPolicyCannotEnableAnExternalEndpoint() {
    var properties = properties();
    assertThatThrownBy(() -> LlmModelProvider.forResponsesAcceptance(defaults, properties, isolated,
        Map.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LlmModelProvider.forResponsesAcceptance(defaults, properties, List.of(),
        Map.of(LlmFeature.CHAT, target(properties)))).isInstanceOf(IllegalArgumentException.class);
  }
}
