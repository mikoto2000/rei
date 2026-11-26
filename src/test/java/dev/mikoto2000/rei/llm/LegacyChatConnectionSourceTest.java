package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatOptions;

class LegacyChatConnectionSourceTest {
  @Test
  void globalConnectionUsesTheSameResolvedPropertiesAsSpringWithoutSavingKeys() throws Exception {
    var properties = new LlmProperties();
    var common = common("http://global.invalid", "PRIVATE_CONNECTION_KEY");
    var chat = new OpenAiChatProperties();
    chat.setBaseUrl("http://chat-override.invalid");
    chat.setOrganizationId("org-fixture");
    chat.setTimeout(Duration.ofMinutes(2));
    var source =
        source(
            properties,
            () -> OpenAiAutoConfigurationUtil.resolveCommonProperties(common, chat),
            () -> "PRIVATE_ENV");
    var accepted = factory(properties, source).capture("default", "chosen-model");
    assertThat(accepted.endpoint().baseUrl()).isEqualTo("http://chat-override.invalid/v1");
    assertThat(accepted.endpoint().model()).isEqualTo("chosen-model");
    assertThat(accepted.authentication()).isEqualTo(LlmAuthentication.API_KEY);
    assertThat(accepted.transport()).containsEntry("organization_id", "org-fixture");
    assertThat(accepted.requestTimeout()).isEqualTo(Duration.ofMinutes(2));
    assertThat(accepted.generation()).containsEntry("top_p", 0.4);
    assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(accepted))
        .doesNotContain("PRIVATE_");
    assertThat(source.credential(accepted)).isEqualTo("PRIVATE_CONNECTION_KEY");
  }

  @Test
  void customFeatureDoesNotReadGlobalKeyOrAccountAttributes() {
    var properties = new LlmProperties();
    var feature = new LlmProperties.Server();
    feature.setBaseUrl("http://feature.invalid");
    feature.setModel("feature-model");
    properties.setFeature(LlmFeature.CHAT, feature);
    var reads = new AtomicInteger();
    var source =
        source(
            properties,
            () -> {
              reads.incrementAndGet();
              throw new AssertionError("A feature connection consulted global authentication");
            },
            () -> {
              throw new AssertionError("A feature connection consulted ambient credentials");
            });
    var accepted = factory(properties, source).capture("default", null);
    assertThat(accepted.endpoint().baseUrl()).isEqualTo("http://feature.invalid/v1");
    assertThat(accepted.authentication()).isEqualTo(LlmAuthentication.NONE);
    assertThat(accepted.transport()).doesNotContainKey("organization_id");
    assertThat(reads).hasValue(0);
  }

  @Test
  void blankGlobalKeyIsExplicitNoAuthAndDoesNotConsultTheEnvironment() {
    var properties = new LlmProperties();
    var common = common("http://global.invalid", "");
    var source =
        source(
            properties,
            () ->
                OpenAiAutoConfigurationUtil.resolveCommonProperties(
                    common, new OpenAiChatProperties()),
            () -> {
              throw new AssertionError("Explicit no-auth consulted ambient credentials");
            });
    assertThat(factory(properties, source).capture("default", null).authentication())
        .isEqualTo(LlmAuthentication.NONE);
  }

  @Test
  void refreshedKeyIsReadOnlyAfterVerifyingTheSamePinnedAudience() {
    var properties = new LlmProperties();
    var common = common("http://global.invalid", "PRIVATE_FIRST");
    var current =
        new AtomicReference<>(
            OpenAiAutoConfigurationUtil.resolveCommonProperties(
                common, new OpenAiChatProperties()));
    var source = source(properties, current::get, () -> null);
    var accepted = factory(properties, source).capture("default", null);
    common.setApiKey("PRIVATE_REFRESHED");
    current.set(
        OpenAiAutoConfigurationUtil.resolveCommonProperties(common, new OpenAiChatProperties()));
    assertThat(source.credential(accepted)).isEqualTo("PRIVATE_REFRESHED");
    common.setBaseUrl("http://other.invalid");
    current.set(
        OpenAiAutoConfigurationUtil.resolveCommonProperties(common, new OpenAiChatProperties()));
    assertThatThrownBy(() -> source.credential(accepted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("binding");
  }

  @Test
  void aNullGlobalUrlUsesTheSameExplicitEnvironmentFallbackAsTheExistingSdk() {
    var properties = new LlmProperties();
    var common = common(null, "PRIVATE_FIRST");
    var url = new AtomicReference<>("http://environment.invalid");
    var source =
        source(
            properties,
            () ->
                OpenAiAutoConfigurationUtil.resolveCommonProperties(
                    common, new OpenAiChatProperties()),
            () -> null,
            url::get);
    var accepted = factory(properties, source).capture("default", null);
    assertThat(accepted.endpoint().baseUrl()).isEqualTo("http://environment.invalid/v1");
    assertThat(source.credential(accepted)).isEqualTo("PRIVATE_FIRST");
    url.set("http://other.invalid");
    assertThatThrownBy(() -> source.credential(accepted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("binding");
  }

  @Test
  void anExplicitFeatureNeverReadsTheEnvironmentReceiver() {
    var properties = new LlmProperties();
    var feature = new LlmProperties.Server();
    feature.setBaseUrl("http://feature.invalid");
    feature.setModel("model");
    properties.setFeature(LlmFeature.CHAT, feature);
    var source =
        source(
            properties,
            () -> {
              throw new AssertionError("Read global connection");
            },
            () -> {
              throw new AssertionError("Read global key");
            },
            () -> {
              throw new AssertionError("Read environment receiver");
            });
    assertThat(factory(properties, source).capture("default", null).endpoint().baseUrl())
        .isEqualTo("http://feature.invalid/v1");
  }

  private LegacyChatConnectionSource source(
      LlmProperties properties,
      java.util.function.Supplier<OpenAiAutoConfigurationUtil.ResolvedConnectionProperties> global,
      java.util.function.Supplier<String> environmentKey) {
    return source(properties, global, environmentKey, () -> null);
  }

  private LegacyChatConnectionSource source(
      LlmProperties properties,
      java.util.function.Supplier<OpenAiAutoConfigurationUtil.ResolvedConnectionProperties> global,
      java.util.function.Supplier<String> environmentKey,
      java.util.function.Supplier<String> environmentBaseUrl) {
    var model = mock(ChatModel.class);
    when(model.getOptions())
        .thenReturn(
            OpenAiChatOptions.builder()
                .model("root-model")
                .baseUrl("http://unrelated-native-default.invalid/v1")
                .apiKey("PRIVATE_NATIVE_OPTIONS")
                .topP(0.4)
                .maxRetries(0)
                .build());
    return new LegacyChatConnectionSource(
        new LlmModelProvider(model, properties),
        properties,
        global,
        environmentKey,
        environmentBaseUrl);
  }

  private RunLlmConnectionFactory factory(
      LlmProperties properties, LegacyChatConnectionSource source) {
    return RunLlmConnectionFactory.withLegacySettings(
        new ChatEndpointCatalog(properties), properties, source::resolve, ignored -> null);
  }

  private OpenAiCommonProperties common(String url, String key) {
    var common = new OpenAiCommonProperties();
    common.setBaseUrl(url);
    common.setApiKey(key);
    return common;
  }
}
