package dev.mikoto2000.rei.llm;

import java.util.function.Function;

/** Resolves immutable settings only; credentials are obtained at the HTTP boundary. */
final class RunLlmConnectionFactory {
  private final ChatEndpointCatalog catalog;
  private final LlmProperties properties;
  private final Function<String, ResolvedLlmEndpoint> legacy;
  private final Function<String, String> registration;

  RunLlmConnectionFactory(
      ChatEndpointCatalog catalog,
      LlmProperties properties,
      Function<String, ResolvedLlmEndpoint> legacy,
      Function<String, String> registration) {
    this.catalog = catalog;
    this.properties = properties;
    this.legacy = legacy;
    this.registration = registration;
  }

  RunLlmConnection capture(String profile, String modelOverride) {
    var connection = catalog.connection(profile);
    var metadata =
        catalog.profiles().stream().filter(p -> p.id().equals(profile)).findFirst().orElseThrow();
    if (!catalog.hasNamedProfiles()) {
      var resolved = legacy.apply(modelOverride);
      return new RunLlmConnection(
          profile,
          false,
          metadata.provider(),
          metadata.authentication(),
          null,
          null,
          resolved,
          metadata.capabilities().effective(),
          generation(resolved));
    }
    if (modelOverride != null && !modelOverride.equals(metadata.model()))
      throw new IllegalStateException("Chat model override resolution is not verified");
    var capabilities = metadata.capabilities().effective();
    var replay =
        connection.getApi() == LlmApiType.CHAT_COMPLETIONS
            ? ReasoningReplayMode.NONE
            : connection.getReasoningReplay();
    if (replay == null)
      throw new IllegalStateException("reasoning-replay must be configured for Responses");
    var settings = java.util.EnumSet.noneOf(LlmCapabilities.Setting.class);
    for (var setting : LlmCapabilities.Setting.values()) {
      var capability =
          switch (setting) {
            case IMAGE_INPUT -> LlmEndpointCapabilities.Capability.VISION;
            default -> LlmEndpointCapabilities.Capability.valueOf(setting.name());
          };
      if (capabilities.get(capability) == LlmEndpointCapabilities.Support.SUPPORTED)
        settings.add(setting);
    }
    Integer output = connection.getMaxOutputTokens();
    if (capabilities.get(LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS)
        != LlmEndpointCapabilities.Support.SUPPORTED) {
      if (output != null)
        throw new IllegalStateException("max-output-tokens is unsupported or unconfirmed");
      output = null;
    } else if (output == null) output = properties.getMaxOutputTokens();
    var resolved =
        new ResolvedLlmEndpoint(
            profile,
            metadata.generation(),
            metadata.api(),
            connection.getBaseUrl(),
            "chat-endpoint:" + profile,
            metadata.model(),
            new LlmCapabilities(replay, metadata.capabilities().maxTools(), settings),
            output,
            connection.getTemperature(),
            properties.getReadTimeout());
    var accountRegistration =
        metadata.accountRef() == null ? null : registration.apply(metadata.accountRef());
    if (metadata.provider() == LlmProvider.CHATGPT_PLAN && accountRegistration == null)
      throw new IllegalStateException("ChatGPT account registration is unavailable");
    return new RunLlmConnection(
        profile,
        true,
        metadata.provider(),
        metadata.authentication(),
        metadata.accountRef(),
        accountRegistration,
        resolved,
        capabilities,
        generation(resolved));
  }

  private static java.util.Map<String, Object> generation(ResolvedLlmEndpoint endpoint) {
    var values = new java.util.LinkedHashMap<String, Object>();
    if (endpoint.maxOutputTokens() != null)
      values.put(
          endpoint.apiType() == LlmApiType.RESPONSES ? "max_output_tokens" : "max_tokens",
          endpoint.maxOutputTokens());
    if (endpoint.temperature() != null) values.put("temperature", endpoint.temperature());
    return values;
  }
}
