package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatEndpointCapabilitiesTest {
  @Test
  void unknownModelCapabilitiesAreNotInventedFromTheHostOrModelName() {
    var view = view(local());
    assertThat(view.at("/usagePath").asText()).isEqualTo("unknown");
    assertThat(view.at("/capabilities/effective/VISION").asText()).isEqualTo("UNKNOWN");
    assertThat(view.at("/capabilities/effective/FUNCTION_CALLING").asText()).isEqualTo("UNKNOWN");
    assertThat(view.at("/capabilities/effective/CHAT_COMPLETIONS").asText()).isEqualTo("UNKNOWN");
    assertThat(view.at("/capabilities/effective/RESPONSES").asText()).isEqualTo("UNSUPPORTED");
  }

  @Test
  void anExplicitModelDeclarationCannotOverrideTransportRestrictions() {
    var settings = local();
    settings.put("rei.llm.features.chat.endpoints.local.model-capabilities.vision", "supported");
    settings.put("rei.llm.features.chat.endpoints.local.server-capabilities.vision", "supported");
    settings.put(
        "rei.llm.features.chat.endpoints.local.model-capabilities.tool-namespace", "supported");
    settings.put(
        "rei.llm.features.chat.endpoints.local.model-capabilities.hosted-mcp", "supported");
    var view = view(settings);
    assertThat(view.at("/capabilities/model/VISION").asText()).isEqualTo("SUPPORTED");
    assertThat(view.at("/capabilities/effective/VISION").asText()).isEqualTo("SUPPORTED");
    assertThat(view.at("/capabilities/model/TOOL_NAMESPACE").asText()).isEqualTo("SUPPORTED");
    assertThat(view.at("/capabilities/route/TOOL_NAMESPACE").asText()).isEqualTo("UNSUPPORTED");
    assertThat(view.at("/capabilities/effective/TOOL_NAMESPACE").asText()).isEqualTo("UNSUPPORTED");
    assertThat(view.at("/capabilities/effective/HOSTED_MCP").asText()).isEqualTo("UNSUPPORTED");
  }

  @Test
  void explicitModelRestrictionsWinOverLegacySupportedSettingDeclarations() {
    var settings = local();
    settings.put("rei.llm.features.chat.endpoints.local.supported-settings[0]", "IMAGE_INPUT");
    settings.put("rei.llm.features.chat.endpoints.local.model-capabilities.vision", "unsupported");
    assertThat(view(settings).at("/capabilities/effective/VISION").asText())
        .isEqualTo("UNSUPPORTED");
  }

  @Test
  void planParametersAndHostedToolsRemainUnsupportedEvenWhenTheModelIsDeclaredCapable() {
    var settings = plan();
    for (var capability :
        java.util.List.of(
            "temperature",
            "top-p",
            "max-output-tokens",
            "previous-response-id",
            "hosted-mcp",
            "hosted-tool-search"))
      settings.put(
          "rei.llm.features.chat.endpoints.subscription.model-capabilities." + capability,
          "supported");
    var view = view(settings);
    assertThat(view.at("/usagePath").asText()).isEqualTo("chatgpt-plan");
    for (var capability :
        java.util.List.of(
            "TEMPERATURE",
            "TOP_P",
            "MAX_OUTPUT_TOKENS",
            "PREVIOUS_RESPONSE_ID",
            "HOSTED_MCP",
            "HOSTED_TOOL_SEARCH"))
      assertThat(view.at("/capabilities/effective/" + capability).asText())
          .isEqualTo("UNSUPPORTED");
    var connection = catalog(settings).connection("subscription");
    assertThat(connection.getReasoningReplay()).isEqualTo(ReasoningReplayMode.ENCRYPTED);
  }

  @Test
  void configuredUsageLabelsAreExplicitAndDoNotChangeTheProvider() {
    var settings = local();
    settings.put("rei.llm.features.chat.endpoints.local.usage-path", "local-llm");
    assertThat(view(settings).at("/usagePath").asText()).isEqualTo("local-llm");
    settings.put("rei.llm.features.chat.endpoints.local.usage-path", "api-billing");
    assertThat(view(settings).at("/usagePath").asText()).isEqualTo("api-billing");
    assertThat(view(settings).at("/provider").asText()).isEqualTo("openai-compatible");
  }

  @Test
  void anApiKeyProfileCannotBeMislabeledAsPlanUsage() {
    var settings = local();
    settings.put("rei.llm.features.chat.endpoints.local.usage-path", "chatgpt-plan");
    assertThatThrownBy(() -> catalog(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.chat.endpoints.local.usage-path");
  }

  @Test
  void planUsageCannotBeMislabeledAsLocalOrApiBilling() {
    var settings = plan();
    settings.put("rei.llm.features.chat.endpoints.subscription.usage-path", "api-billing");
    assertThatThrownBy(() -> catalog(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.chat.endpoints.subscription.usage-path");
  }

  @Test
  void endpointToolLimitsKeepTheirExistingOverrideSemantics() {
    var settings = local();
    settings.put("rei.llm.max-tools", "36");
    settings.put("rei.llm.features.chat.endpoints.local.max-tools", "64");
    assertThat(view(settings).at("/capabilities/maxTools").asInt()).isEqualTo(64);
    settings.remove("rei.llm.features.chat.endpoints.local.max-tools");
    assertThat(view(settings).at("/capabilities/maxTools").isNull()).isTrue();
    assertThat(view(Map.of("rei.llm.max-tools", "36")).at("/capabilities/maxTools").asInt())
        .isEqualTo(36);
  }

  @Test
  void flatResponsesNamespacesAndUnimplementedInputsAreNotAdvertisedAsAvailable() {
    var settings = local();
    settings.put("rei.llm.features.chat.endpoints.local.api", "responses");
    settings.put("rei.llm.features.chat.endpoints.local.server-capabilities.responses", "supported");
    var view = view(settings);
    assertThat(view.at("/capabilities/effective/RESPONSES").asText()).isEqualTo("SUPPORTED");
    assertThat(view.at("/capabilities/effective/TOOL_NAMESPACE").asText()).isEqualTo("UNSUPPORTED");
    assertThat(view.at("/capabilities/effective/FILE_INPUT").asText()).isEqualTo("UNSUPPORTED");
    assertThat(view.at("/capabilities/effective/HOSTED_WEB_SEARCH").asText())
        .isEqualTo("UNSUPPORTED");
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.CsvSource({
    "model-capabilities.vision,PRIVATE_CANARY,model-capabilities.vision",
    "model-capabilities.PRIVATE_CANARY,supported,model-capabilities",
    "usage-path,PRIVATE_CANARY,usage-path",
    "reasoning-replay,plaintext,reasoning-replay"
  })
  void invalidDeclarationsHaveSafeFieldDiagnostics(String key, String value, String field) {
    var settings = key.equals("reasoning-replay") ? plan() : local();
    String endpoint = key.equals("reasoning-replay") ? "subscription" : "local";
    settings.put("rei.llm.features.chat.endpoints." + endpoint + "." + key, value);
    assertThatThrownBy(() -> catalog(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rei.llm.features.chat.endpoints." + endpoint + "." + field)
        .hasMessageNotContaining("PRIVATE_CANARY");
  }

  private JsonNode view(Map<String, String> settings) {
    return new ObjectMapper().valueToTree(catalog(settings).profiles().getFirst());
  }

  private ChatEndpointCatalog catalog(Map<String, String> settings) {
    return new ChatEndpointCatalog(
        new Binder(new MapConfigurationPropertySource(settings))
            .bind("rei.llm", LlmProperties.class)
            .get());
  }

  private HashMap<String, String> local() {
    return new HashMap<>(
        Map.of(
            "rei.llm.features.chat.active", "local",
            "rei.llm.features.chat.endpoints.local.base-url", "http://localhost:11434",
            "rei.llm.features.chat.endpoints.local.model", "local-model"));
  }

  private HashMap<String, String> plan() {
    return new HashMap<>(
        Map.of(
            "rei.llm.features.chat.active", "subscription",
            "rei.llm.features.chat.endpoints.subscription.provider", "chatgpt-plan",
            "rei.llm.features.chat.endpoints.subscription.api", "responses",
            "rei.llm.features.chat.endpoints.subscription.account-ref", "personal",
            "rei.llm.features.chat.endpoints.subscription.model", "fixture-model"));
  }
}
