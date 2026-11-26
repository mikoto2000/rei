package dev.mikoto2000.rei.llm;

import java.util.List;
import java.util.Objects;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

/** Explicit named-profile acceptance, separate from the closed legacy production tuple. */
final class ResponsesProfilePolicy {
  private ResponsesProfilePolicy() {}

  static void requireConnection(RunLlmConnection connection) {
    var endpoint = connection.endpoint();
    if (!connection.named()
        || endpoint.apiType() != LlmApiType.RESPONSES
        || !connection.profileId().equals(endpoint.endpointId())
        || !("chat-endpoint:" + connection.profileId()).equals(endpoint.credentialRef())
        || endpoint.model() == null
        || endpoint.model().isBlank()
        || endpoint.model().codePoints().anyMatch(Character::isISOControl))
      throw new IllegalStateException("Named Responses connection binding is invalid");
    requireCapability(connection, LlmEndpointCapabilities.Capability.RESPONSES);
    if (connection.provider() == LlmProvider.CHATGPT_PLAN)
      throw new IllegalStateException("ChatGPT Responses requires its verified account policy");
    if (connection.accountRef() != null
        || connection.accountRegistration() != null
        || connection.authentication() != LlmAuthentication.API_KEY
            && connection.authentication() != LlmAuthentication.NONE)
      throw new IllegalStateException("Named Responses authentication binding is invalid");
    if (connection.provider() == LlmProvider.OPENAI_API
        && (connection.authentication() != LlmAuthentication.API_KEY
            || !"https://api.openai.com/v1".equals(endpoint.baseUrl())))
      throw new IllegalStateException("OpenAI Responses requires its API key resource binding");
    if (endpoint.readTimeout().compareTo(java.time.Duration.ofMillis(1)) < 0)
      throw new IllegalStateException("Responses read timeout must be positive");
    if (endpoint.maxOutputTokens() != null)
      requireCapability(connection, LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS);
    if (endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.NONE)
      requireCapability(connection, LlmEndpointCapabilities.Capability.REASONING_REPLAY);
  }

  static void requireCapability(
      RunLlmConnection connection, LlmEndpointCapabilities.Capability capability) {
    if (connection.capabilities().get(capability) != LlmEndpointCapabilities.Support.SUPPORTED)
      throw new IllegalStateException(
          "Responses capability is unsupported or unconfirmed: " + capability);
  }

  static void requireRequest(RunLlmConnection connection, OpenAiResponsesChatOptions options) {
    var context = options.getToolContext();
    if (context == null
        || !Objects.equals(
            connection,
            context.get(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY)))
      throw new IllegalArgumentException(
          "Responses request must retain the accepted Run connection");
    if (options.getMaxOutputTokens() != null)
      requireCapability(connection, LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS);
    var include = options.getInclude();
    if (include != null
        && !include.isEmpty()
        && (connection.endpoint().capabilities().reasoningReplay() != ReasoningReplayMode.ENCRYPTED
            || !include.equals(List.of("reasoning.encrypted_content"))))
      throw new IllegalArgumentException("Responses include is incompatible with the replay mode");
    if (options.getServiceTier() != null
        || options.getPromptCacheKey() != null
        || options.getSafetyIdentifier() != null
        || options.getMetadata() != null && !options.getMetadata().isEmpty())
      throw new IllegalArgumentException(
          "Responses platform setting requires a supported profile contract");
    var tools = options.getToolCallbacks();
    if (tools != null && !tools.isEmpty())
      requireCapability(connection, LlmEndpointCapabilities.Capability.FUNCTION_CALLING);
  }
}
