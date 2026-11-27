package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Metadata only: no HTTP, inference, token refresh or ambient model access. */
class ChatEndpointDescriptionTest {
  @Test
  void httpModelSelectionCarriesIdentityRejectsStaleWritesAndKeepsModelOnlyCompatibility()
      throws Exception {
    try (var service = service(settings())) {
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  new dev.mikoto2000.rei.web.ModelController(service))
              .setControllerAdvice(new dev.mikoto2000.rei.web.ApiExceptionHandler())
              .build();
      String path = "/api/v1/models/current";
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.endpoint")
                  .value("first"));
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                  .contentType("application/json")
                  .content("{\"model\":\"first-model\",\"endpoint\":\"first\"}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.endpoint")
                  .value("first"));
      service.select("second");
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                  .contentType("application/json")
                  .content("{\"model\":\"stale-model\",\"endpoint\":\"first\"}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isConflict());
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                  .contentType("application/json")
                  .content("{\"model\":\"second-model\"}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.endpoint")
                  .value("second"));
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                  .contentType("application/json")
                  .content("{\"model\":\"second-model\",\"apiKey\":\"PRIVATE_VALUE\"}"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                  .isBadRequest())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                  .string(
                      org.hamcrest.Matchers.not(
                          org.hamcrest.Matchers.containsString("PRIVATE_VALUE"))));
      assertThat(service.selection().model()).isEqualTo("second-model");
    }
  }

  @Test
  void publicGenerationMatchesConfiguredNativeSettingsAndExplicitProvider() {
    var values = settings();
    values.put(prefix("first") + "provider", "openai-api");
    values.put(prefix("first") + "base-url", "https://api.openai.com/v1");
    values.put(prefix("first") + "generation.reasoning-effort", "low");
    values.put(prefix("first") + "server-capabilities.reasoning-effort", "supported");
    values.put(prefix("first") + "model-capabilities.reasoning-effort", "supported");
    try (var service = service(values)) {
      assertThat(service.currentEndpoint().generationSettings())
          .containsEntry("reasoning_effort", "low")
          .containsEntry("max_completion_tokens", 256)
          .doesNotContainKey("max_tokens");
    }
  }

  @Test
  void rejectedConfiguredGenerationIsReportedWithoutReadingCredentials() {
    var values = settings();
    values.put(prefix("first") + "generation.store", "true");
    try (var service = service(values)) {
      assertThat(service.currentEndpoint().unappliedSettings()).containsKey("store");
      assertThat(service.currentEndpoint().unavailableReason()).contains("store");
      assertThat(service.currentEndpoint().generationSettings()).doesNotContainKey("store");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "chat-completions,unknown", "chat-completions,unsupported",
    "streaming,unknown", "streaming,unsupported"
  })
  void namedChatMetadataExplainsTheSameApiAdmissionRequirements(String capability, String support) {
    var values = settings();
    values.put(prefix("first") + "server-capabilities." + capability, support);
    try (var service = service(values)) {
      assertThat(service.currentEndpoint().unavailableReason())
          .contains(capability.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    }
  }

  @Test
  void modelControlsRejectAStaleEndpointInsteadOfChangingTheNewSelection() throws Exception {
    var values = settings();
    values.put(prefix("first") + "server-capabilities.chat-completions", "supported");
    values.put(prefix("first") + "server-capabilities.streaming", "supported");
    try (var service = service(values)) {
      var controller = new dev.mikoto2000.rei.web.ModelController(service);
      var request =
          new ObjectMapper()
              .readValue(
                  "{\"model\":\"stale-model\",\"endpoint\":\"first\"}",
                  dev.mikoto2000.rei.web.ModelController.Selection.class);
      service.select("second");
      assertThatThrownBy(() -> controller.select(request))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
          .satisfies(error ->
              assertThat(
                      ((org.springframework.web.server.ResponseStatusException) error)
                          .getStatusCode()
                          .value())
                  .isEqualTo(409));
      assertThat(service.selection().model()).isEqualTo("second-model");
    }
  }

  @Test
  void explicitSecondModelDeclarationsAreProjectedWithoutBorrowingTheFirstModelsCapabilities() {
    var values = settings();
    values.remove(prefix("first") + "temperature");
    values.remove(prefix("first") + "max-output-tokens");
    values.put(
        prefix("first") + "model-capabilities-by-name.[confirmed-second].max-output-tokens",
        "supported");
    values.put(
        prefix("first") + "model-capabilities-by-name.[confirmed-second].vision", "unsupported");
    try (var service = service(values)) {
      service.selectModel("confirmed-second");
      var selected = service.currentEndpoint();
      assertThat(selected.profile().capabilities().effective())
          .containsEntry(
              LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
              LlmEndpointCapabilities.Support.SUPPORTED)
          .containsEntry(
              LlmEndpointCapabilities.Capability.VISION,
              LlmEndpointCapabilities.Support.UNSUPPORTED);
      assertThat(selected.generationSettings()).containsEntry("max_tokens", 4096);
      service.selectModel("unknown-third");
      assertThat(service.currentEndpoint().profile().capabilities().effective())
          .containsEntry(
              LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
              LlmEndpointCapabilities.Support.UNKNOWN);
    }
  }

  @Test
  void descriptionsShowOnlyDeclaredUsageAndSafeEffectiveSettings() throws Exception {
    try (var service = service(settings())) {
      var entries = service.endpoints();
      assertThat(entries).hasSize(2);
      var first = entries.getFirst();
      assertThat(first.profile().id()).isEqualTo("first");
      assertThat(first.active()).isTrue();
      assertThat(first.profile().usagePath()).isEqualTo(LlmUsagePath.LOCAL_LLM);
      assertThat(first.authenticationState()).isEqualTo("configured");
      assertThat(first.generationSettings())
          .containsEntry("temperature", 0.7)
          .containsEntry("max_tokens", 256);
      assertThat(first.reasoningReplay()).isEqualTo(ReasoningReplayMode.NONE);
      assertThat(entries.getLast().profile().usagePath()).isEqualTo(LlmUsagePath.UNKNOWN);
      assertThat(entries.getLast().authenticationState()).isEqualTo("not-required");
      var json = new ObjectMapper().writeValueAsString(entries);
      assertThat(json)
          .doesNotContain("PRIVATE_PROFILE_KEY", "first.invalid", "apiKey", "credentialRef");
      assertThatThrownBy(() -> first.generationSettings().put("temperature", 1.0))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void changingModelInvalidatesModelSpecificDeclarationsInTheView() {
    var values = settings();
    values.remove(prefix("first") + "temperature");
    values.remove(prefix("first") + "max-output-tokens");
    try (var service = service(values)) {
      assertThat(service.endpoints()).hasSize(2);
      assertThat(service.endpoints().getFirst().generationSettings())
          .containsEntry("max_tokens", 4096);
      service.selectModel("unconfirmed-model");
      var selected = service.endpoints().getFirst();
      assertThat(selected.profile().model()).isEqualTo("unconfirmed-model");
      assertThat(selected.profile().capabilities().effective())
          .containsEntry(
              LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
              LlmEndpointCapabilities.Support.UNKNOWN);
      assertThat(selected.generationSettings()).doesNotContainKey("max_tokens");
      assertThat(selected.unappliedSettings()).containsKey("max_tokens");
    }
  }

  @Test
  void unsupportedExplicitSettingsAreReportedAsUnavailableInsteadOfSilentlyApplied() {
    var values = settings();
    values.remove(prefix("first") + "server-capabilities.temperature");
    values.remove(prefix("first") + "model-capabilities.temperature");
    try (var service = service(values)) {
      assertThat(service.endpoints()).hasSize(2);
      var first = service.endpoints().getFirst();
      assertThat(first.generationSettings()).doesNotContainKey("temperature");
      assertThat(first.unappliedSettings()).containsKey("temperature");
      assertThat(first.unavailableReason()).contains("temperature");
    }
  }

  @Test
  void subscriptionMetadataDoesNotTreatConfiguredAccountLabelAsAuthenticated() {
    var values = new HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "subscription");
    values.put(prefix("subscription") + "provider", "chatgpt-plan");
    values.put(prefix("subscription") + "api", "responses");
    values.put(prefix("subscription") + "account-ref", "personal");
    values.put(prefix("subscription") + "model", "configured-model");
    try (var service = service(values)) {
      assertThat(service.endpoints()).hasSize(1);
      var entry = service.endpoints().getFirst();
      assertThat(entry.profile().accountRef()).isEqualTo("personal");
      assertThat(entry.profile().usagePath()).isEqualTo(LlmUsagePath.CHATGPT_PLAN);
      assertThat(entry.authenticationState()).isEqualTo("unverified");
      assertThat(entry.unavailableReason()).isNotBlank();
      assertThat(entry.reasoningReplay()).isEqualTo(ReasoningReplayMode.ENCRYPTED);
      assertThat(entry.generationSettings()).doesNotContainKey("max_output_tokens");
    }
  }

  @Test
  void declaredLocalResponsesReportsTheImplementedPolicyWithoutPerformingInference() {
    var values = settings();
    values.put(prefix("second") + "server-capabilities.responses", "supported");
    values.put(prefix("second") + "server-capabilities.streaming", "supported");
    try (var service = service(values)) {
      var endpoint = service.endpoints().getLast();
      assertThat(endpoint.unavailableReason()).isNull();
      assertThat(endpoint.authenticationState()).isEqualTo("not-required");
      assertThat(endpoint.generationSettings()).doesNotContainKey("max_output_tokens");
      assertThat(endpoint.selectionScope()).contains("Inference not verified");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = LlmEndpointCapabilities.Support.class,
      names = {"UNKNOWN", "UNSUPPORTED"})
  void metadataNamesTheProtocolCapabilityThatPreventsAdmission(
      LlmEndpointCapabilities.Support support) {
    var values = settings();
    values.put(prefix("second") + "server-capabilities.responses", support.name());
    values.put(prefix("second") + "server-capabilities.streaming", "supported");
    try (var service = service(values)) {
      assertThat(service.endpoints().getLast().unavailableReason()).contains("RESPONSES");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = LlmEndpointCapabilities.Support.class,
      names = {"UNKNOWN", "UNSUPPORTED"})
  void metadataNamesMissingStreamingForNormalChat(LlmEndpointCapabilities.Support support) {
    var values = settings();
    values.put(prefix("second") + "server-capabilities.responses", "supported");
    values.put(prefix("second") + "server-capabilities.streaming", support.name());
    try (var service = service(values)) {
      assertThat(service.endpoints().getLast().unavailableReason()).contains("STREAMING");
    }
  }

  private static String prefix(String name) {
    return "rei.llm.features.chat.endpoints." + name + ".";
  }

  private Map<String, String> settings() {
    var values = new HashMap<String, String>();
    values.put("rei.llm.max-output-tokens", "4096");
    values.put("rei.llm.features.chat.active", "first");
    values.put(prefix("first") + "base-url", "http://first.invalid/v1");
    values.put(prefix("first") + "model", "first-model");
    values.put(prefix("first") + "authentication", "api-key");
    values.put(prefix("first") + "api-key", "PRIVATE_PROFILE_KEY");
    values.put(prefix("first") + "usage-path", "local-llm");
    values.put(prefix("first") + "temperature", "0.7");
    values.put(prefix("first") + "max-output-tokens", "256");
    for (var type : java.util.List.of("server-capabilities.", "model-capabilities.")) {
      values.put(prefix("first") + type + "temperature", "supported");
      values.put(prefix("first") + type + "max-output-tokens", "supported");
    }
    values.put(prefix("second") + "base-url", "http://second.invalid/v1");
    values.put(prefix("second") + "model", "second-model");
    values.put(prefix("second") + "authentication", "none");
    values.put(prefix("second") + "api", "responses");
    values.put(prefix("second") + "reasoning-replay", "none");
    return values;
  }

  private ChatConnectionService service(Map<String, String> values) {
    var properties =
        new Binder(new MapConfigurationPropertySource(values))
            .bind("rei.llm", LlmProperties.class)
            .get();
    var root = mock(ChatModel.class);
    when(root.getOptions()).thenThrow(new AssertionError("Metadata accessed ambient model"));
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenThrow(new AssertionError("Metadata accessed ambient model holder"));
    var beans = new StaticListableBeanFactory();
    return new ChatConnectionService(
        new ChatEndpointCatalog(properties),
        properties,
        new LlmModelProvider(root, properties),
        holder,
        beans.getBeanProvider(OpenAiConnectionDetails.class),
        beans.getBeanProvider(OpenAiCommonProperties.class),
        beans.getBeanProvider(OpenAiChatProperties.class),
        beans.getBeanProvider(dev.mikoto2000.rei.llm.capture.CaptureStore.class));
  }
}
