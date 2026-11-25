package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.llm.discovery.ToolDiscoveryProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/** Published examples bind real properties; a MockEnvironment never imports actual API keys. */
class ResponsesConfigurationExampleTest {
  private MockEnvironment environment(String file) throws Exception {
    var environment =
        new MockEnvironment()
            .withProperty("OPENAI_API_KEY", "owned-example-key")
            .withProperty("REI_OPENAI_API_KEY", "owned-local-key")
            .withProperty("rei.llm.read-timeout", "10m")
            .withProperty("rei.context-compression.summary-enable-thinking", "false");
    var sources =
        new YamlPropertySourceLoader().load("owned-example", new FileSystemResource(file));
    for (var source : sources) environment.getPropertySources().addFirst(source);
    return environment;
  }

  @Test
  void acceptedExampleBindsBothNativeFeaturesAndAFittingSummaryBudgetWithoutChangingDefaultCc()
      throws Exception {
    var binder = Binder.get(environment("docs/responses-openai-accepted.example.yaml"));
    var properties = binder.bind("rei.llm", LlmProperties.class).get();
    assertThat(properties.feature(LlmFeature.CHAT).getApiKey()).isEqualTo("owned-example-key");
    var defaults = org.mockito.Mockito.mock(ChatModel.class);
    var provider = new LlmModelProvider(defaults, properties);
    for (String feature : java.util.List.of(LlmFeature.CHAT, LlmFeature.OUTPUT_LIMIT_PLANNER)) {
      assertThat(provider.chatModel(feature)).isInstanceOf(ResponsesAdapter.class);
      assertThat(provider.resolvedEndpoint(feature).readTimeout())
          .isEqualTo(Duration.ofSeconds(120));
      assertThat(provider.resolvedEndpoint(feature).maxOutputTokens()).isEqualTo(2048);
    }
    assertThat(provider.chatModel(LlmFeature.MEMORY)).isSameAs(defaults);
    assertThat(provider.resolvedEndpoint(LlmFeature.MEMORY).readTimeout())
        .isEqualTo(Duration.ofMinutes(10));
    var compression =
        binder.bind("rei.context-compression", ContextCompressionProperties.class).get();
    compression.validate();
    assertThat(compression.getSummaryEnableThinking()).isNull();
    assertThat(compression.contextLimit("gpt-5.6-sol")).isEqualTo(32000);
    assertThat(compression.getSummaryTokens() + compression.getSummaryGenerationReserve())
        .isEqualTo(1536)
        .isLessThanOrEqualTo(provider.resolvedEndpoint(LlmFeature.CHAT).maxOutputTokens());
    var discovery = binder.bind("rei.llm.tool-discovery", ToolDiscoveryProperties.class).get();
    assertThat(discovery.isEnabled()).isTrue();
    assertThat(discovery.getMaxActiveTools())
        .isLessThanOrEqualTo(provider.resolvedEndpoint(LlmFeature.CHAT).capabilities().maxTools());
    assertThat(properties.getOutputLimit().isLlmCallBudgetEnabled()).isTrue();
    assertThat(properties.getOutputLimit().getMaxLlmCallsPerRun()).isEqualTo(64);
  }

  @Test
  void localDiagnosticExampleRemainsClosedUnderTheNormalProductionGate() throws Exception {
    var properties =
        Binder.get(environment("docs/responses-local-unverified.example.yaml"))
            .bind("rei.llm", LlmProperties.class)
            .get();
    var provider = new LlmModelProvider(org.mockito.Mockito.mock(ChatModel.class), properties);
    assertThatThrownBy(() -> provider.chatModel(LlmFeature.CHAT))
        .isInstanceOf(IllegalStateException.class);
  }
}
