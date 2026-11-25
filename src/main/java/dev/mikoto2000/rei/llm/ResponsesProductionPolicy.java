package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modeloptions.ResponsesConfigurationException;
import java.time.Duration;
import java.util.Set;

/** Connection and request constraints for configured OpenAI Responses models. */
final class ResponsesProductionPolicy {
  private static final Duration TIMEOUT = Duration.ofSeconds(120);

  private ResponsesProductionPolicy() {}

  /** Cap only the production Responses connection. */
  static Duration readTimeout(Duration configured) {
    return configured.compareTo(TIMEOUT) > 0 ? TIMEOUT : configured;
  }

  static void requireConfigured(
      LlmProperties.Server server, ResolvedLlmEndpoint endpoint, String feature) {
    if (server.getModel() == null
        || server.getModel().isBlank()
        || server.isFallbackToDefault()
        || server.getApiKey() == null
        || server.getApiKey().isBlank()
        || "dummy-key".equals(server.getApiKey())) throw unavailable();
    if (!feature.equals(endpoint.endpointId())) throw unavailable();
    requireEndpoint(endpoint);
  }

  static void requireEndpoint(ResolvedLlmEndpoint endpoint) {
    String feature = endpoint.endpointId();
    if (!Set.of(LlmFeature.CHAT, LlmFeature.OUTPUT_LIMIT_PLANNER).contains(feature)
        || endpoint.model() == null
        || endpoint.model().isBlank()) throw unavailable();
    var accepted =
        new ResolvedLlmEndpoint(
            feature,
            1,
            LlmApiType.RESPONSES,
            "https://api.openai.com/v1",
            "feature:" + feature,
            endpoint.model(),
            new LlmCapabilities(
                ReasoningReplayMode.ENCRYPTED,
                36,
                Set.of(LlmCapabilities.Setting.REASONING_EFFORT)),
            endpoint.maxOutputTokens(),
            null,
            TIMEOUT);
    if (!accepted.equals(endpoint)) throw unavailable();
  }

  /** Reapply the accepted request values without mutating the caller's authority or options. */
  static org.springframework.ai.openai.responses.OpenAiResponsesChatOptions requestOptions(
      org.springframework.ai.openai.responses.OpenAiResponsesChatOptions options) {
    var include = options.getInclude();
    var metadata = options.getMetadata();
    if (options.getReasoningEffort() != null && !"low".equals(options.getReasoningEffort())
        || options.getServiceTier() != null && !"default".equals(options.getServiceTier())
        || metadata != null && !metadata.isEmpty()
        || options.getPromptCacheKey() != null
        || options.getSafetyIdentifier() != null
        || include != null
            && !include.isEmpty()
            && !include.equals(java.util.List.of("reasoning.encrypted_content")))
      throw new IllegalArgumentException(
          "Responses request differs from the supported OpenAI settings");
    return options.mutate().reasoningEffort("low").serviceTier("default").build();
  }

  private static ResponsesConfigurationException unavailable() {
    return new ResponsesConfigurationException();
  }
}
