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
    if (endpoint.readTimeout().compareTo(java.time.Duration.ofMillis(1)) < 0)
      throw new IllegalStateException("Responses read timeout must be positive");
    if (connection.provider() == LlmProvider.CHATGPT_PLAN) {
      if (connection.authentication() != LlmAuthentication.CHATGPT_OAUTH
          || !"https://api.openai.com/v1".equals(endpoint.baseUrl())
          || connection.accountRef() == null
          || !connection.accountRef().matches("[A-Za-z0-9_-]{1,64}")
          || connection.accountRegistration() == null
          || connection.accountRegistration().isBlank()
          || connection.accountRegistration().codePoints().anyMatch(Character::isISOControl)
          || endpoint.capabilities().reasoningReplay() != ReasoningReplayMode.ENCRYPTED
          || endpoint.maxOutputTokens() != null
          || endpoint.temperature() != null
          || !RunTransportSettings.binding(connection.transport()).isEmpty())
        throw new IllegalStateException("ChatGPT Responses account or resource binding is invalid");
      requireCapability(connection, LlmEndpointCapabilities.Capability.STREAMING);
      requireCapability(connection, LlmEndpointCapabilities.Capability.REASONING_REPLAY);
      return;
    }
    if (connection.accountRef() != null
        || connection.accountRegistration() != null
        || connection.authentication() != LlmAuthentication.API_KEY
            && connection.authentication() != LlmAuthentication.NONE)
      throw new IllegalStateException("Named Responses authentication binding is invalid");
    if (connection.provider() == LlmProvider.OPENAI_API
        && (connection.authentication() != LlmAuthentication.API_KEY
            || !"https://api.openai.com/v1".equals(endpoint.baseUrl())))
      throw new IllegalStateException("OpenAI Responses requires its API key resource binding");
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

  private static void rejectUnsupported(Object value, String field) {
    if (value != null)
      throw new IllegalArgumentException(
          "Responses setting is unsupported for this profile: " + field);
  }

  static void requireRequest(RunLlmConnection connection, OpenAiResponsesChatOptions options) {
    var context = options.getToolContext();
    var metadata = options.getMetadata();
    if (context == null
        || !Objects.equals(
            connection,
            context.get(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY)))
      throw new IllegalArgumentException(
          "Responses request must retain the accepted Run connection");
    if (connection.provider() == LlmProvider.CHATGPT_PLAN) {
      rejectUnsupported(options.getMaxOutputTokens(), "max_output_tokens");
      rejectUnsupported(options.getTemperature(), "temperature");
      rejectUnsupported(options.getTopP(), "top_p");
      rejectUnsupported(options.getMaxToolCalls(), "max_tool_calls");
      rejectUnsupported(options.getTruncation(), "truncation");
      rejectUnsupported(metadata, "metadata");
      var choice = options.getToolChoice();
      if (choice != null
          && !(choice instanceof String value
              && java.util.Set.of("auto", "none", "required").contains(value)))
        throw new IllegalArgumentException(
            "ChatGPT Responses tool_choice supports auto, none or required;"
                + " individual namespaced selection is unconfirmed");
    }
    if (options.getMaxOutputTokens() != null)
      requireCapability(connection, LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS);
    var include = options.getInclude();
    if (include != null
        && !include.isEmpty()
        && (connection.endpoint().capabilities().reasoningReplay() != ReasoningReplayMode.ENCRYPTED
            || !include.equals(List.of("reasoning.encrypted_content"))))
      throw new IllegalArgumentException("Responses include is incompatible with the replay mode");
    rejectUnsupported(options.getServiceTier(), "service_tier");
    rejectUnsupported(options.getPromptCacheKey(), "prompt_cache_key");
    rejectUnsupported(options.getSafetyIdentifier(), "safety_identifier");
    if (metadata != null && !metadata.isEmpty()) rejectUnsupported(metadata, "metadata");
    var tools = options.getToolCallbacks();
    if (tools != null && !tools.isEmpty()) {
      requireCapability(connection, LlmEndpointCapabilities.Capability.FUNCTION_CALLING);
      if (connection.provider() == LlmProvider.CHATGPT_PLAN)
        requireCapability(connection, LlmEndpointCapabilities.Capability.TOOL_NAMESPACE);
    }
  }
}
