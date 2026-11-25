package dev.mikoto2000.rei.llm;

import java.time.Duration;
import java.util.Set;

/** Fixed effective envelope accepted by the 2026-11-25 real operational trials. */
final class ResponsesProductionProfile {
  static final String OPENAI = "openai-gpt-5.6-sol-low-20261125";
  private static final Duration TIMEOUT = Duration.ofSeconds(120);

  private ResponsesProductionProfile() {}

  static boolean declared(LlmProperties.Server server) {
    return server != null
        && server.getAcceptedResponsesProfile() != null
        && !server.getAcceptedResponsesProfile().isBlank();
  }

  /** Cap only the opted-in native connection; never increase an existing timeout. */
  static Duration readTimeout(LlmProperties.Server server, Duration configured) {
    return server != null
            && server.getApi() == LlmApiType.RESPONSES
            && OPENAI.equals(server.getAcceptedResponsesProfile())
            && configured.compareTo(TIMEOUT) > 0
        ? TIMEOUT
        : configured;
  }

  static void requireAccepted(
      LlmProperties.Server server, ResolvedLlmEndpoint endpoint, String feature) {
    if (!OPENAI.equals(server.getAcceptedResponsesProfile())
        || !Set.of(LlmFeature.CHAT, LlmFeature.OUTPUT_LIMIT_PLANNER).contains(feature)
        || server.isFallbackToDefault()
        || server.getApiKey() == null
        || server.getApiKey().isBlank()
        || "dummy-key".equals(server.getApiKey())) throw unavailable();
    if (!feature.equals(endpoint.endpointId())) throw unavailable();
    requireEndpoint(endpoint);
  }

  static void requireEndpoint(ResolvedLlmEndpoint endpoint) {
    String feature = endpoint.endpointId();
    if (!Set.of(LlmFeature.CHAT, LlmFeature.OUTPUT_LIMIT_PLANNER).contains(feature))
      throw unavailable();
    var accepted =
        new ResolvedLlmEndpoint(
            feature,
            1,
            LlmApiType.RESPONSES,
            "https://api.openai.com/v1",
            "feature:" + feature,
            "gpt-5.6-sol",
            new LlmCapabilities(
                ReasoningReplayMode.ENCRYPTED,
                36,
                Set.of(LlmCapabilities.Setting.REASONING_EFFORT)),
            2048,
            null,
            TIMEOUT);
    if (!accepted.equals(endpoint)) throw unavailable();
  }

  /** Reapply the accepted request values without mutating the caller's authority or options. */
  static org.springframework.ai.openai.responses.OpenAiResponsesChatOptions requestOptions(
      org.springframework.ai.openai.responses.OpenAiResponsesChatOptions options) {
    var include = options.getInclude();
    if (options.getReasoningEffort() != null && !"low".equals(options.getReasoningEffort())
        || options.getServiceTier() != null && !"default".equals(options.getServiceTier())
        || options.getMetadata() != null && !options.getMetadata().isEmpty()
        || options.getPromptCacheKey() != null
        || options.getSafetyIdentifier() != null
        || include != null
            && !include.isEmpty()
            && !include.equals(java.util.List.of("reasoning.encrypted_content")))
      throw new IllegalArgumentException(
          "Responses request differs from the accepted operational profile");
    return options.mutate().reasoningEffort("low").serviceTier("default").build();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException(
        "Responses requires an accepted operational profile and exact effective settings");
  }
}
