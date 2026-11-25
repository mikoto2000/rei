package dev.mikoto2000.rei.llm;

import java.util.function.Supplier;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil.ResolvedConnectionProperties;

/** Resolves the existing feature/global connection without exposing its credentials to a Run. */
final class LegacyChatConnectionSource {
  private final LlmModelProvider models;
  private final LlmProperties.Server feature;
  private final Supplier<ResolvedConnectionProperties> global;
  private final Supplier<String> environmentKey;

  LegacyChatConnectionSource(
      LlmModelProvider models,
      LlmProperties properties,
      Supplier<ResolvedConnectionProperties> global,
      Supplier<String> environmentKey) {
    this.models = models;
    this.feature = properties.feature(LlmFeature.CHAT);
    this.global = global;
    this.environmentKey = environmentKey;
  }

  RunLlmConnectionFactory.LegacySettings resolve(String model) {
    return current(model).settings();
  }

  String credential(RunLlmConnection accepted) {
    if (accepted.named()) throw changed();
    var current = current(accepted.endpoint().model());
    var endpoint = current.settings().endpoint();
    if (accepted.authentication() != current.settings().authentication()
        || accepted.endpoint().generation() != endpoint.generation()
        || accepted.endpoint().apiType() != endpoint.apiType()
        || !java.util.Objects.equals(accepted.endpoint().baseUrl(), endpoint.baseUrl())
        || !accepted.endpoint().credentialRef().equals(endpoint.credentialRef())
        || !RunTransportSettings.binding(accepted.transport())
            .equals(RunTransportSettings.binding(current.settings().transport()))) throw changed();
    if (current.settings().authentication() != LlmAuthentication.API_KEY)
      throw new IllegalStateException("Legacy connection has no API-key credentials");
    return current.key().get();
  }

  private record Keys(LlmAuthentication authentication, Supplier<String> value) {}

  private record Current(RunLlmConnectionFactory.LegacySettings settings, Supplier<String> key) {}

  private Current current(String selected) {
    var options = models.requestOptions(LlmFeature.CHAT, selected, true);
    var previous = models.resolvedEndpoint(LlmFeature.CHAT);
    String model = selected == null ? options.getModel() : selected;
    if (model == null || model.isBlank() || model.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalStateException("Legacy chat model must be explicit");
    String url;
    Keys keys;
    var transport = new java.util.LinkedHashMap<String, String>();
    var timeout = nativeTimeout(options);
    if (feature != null && feature.hasCustomServer()) {
      url = OpenAiCompatibleEndpoint.baseUrl(feature.getBaseUrl());
      keys = explicit(feature.getApiKey());
      if (timeout == null)
        timeout = org.springframework.ai.openai.AbstractOpenAiOptions.DEFAULT_TIMEOUT;
    } else {
      var connection = java.util.Objects.requireNonNull(global.get(), "global connection");
      if (connection.isMicrosoftFoundry()
          || connection.isGitHubModels()
          || connection.getMicrosoftDeploymentName() != null
          || connection.getMicrosoftFoundryServiceVersion() != null
          || connection.getProxy() != null)
        throw new IllegalStateException(
            "Legacy connection requires an explicitly supported transport contract");
      url = connection.getBaseUrl();
      if (url == null) url = com.openai.core.ClientOptions.PRODUCTION_URL;
      url = OpenAiCompatibleEndpoint.baseUrl(url);
      keys = globalKeys(connection);
      if (timeout == null) timeout = connection.getTimeout();
      if (connection.getOrganizationId() != null && !connection.getOrganizationId().isBlank())
        transport.put("organization_id", connection.getOrganizationId());
      var headers = connection.getCustomHeaders();
      if (headers != null)
        headers.forEach((name, value) -> {
          var field =
              switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "openai-organization" -> "organization_id";
                case "openai-project" -> "project_id";
                case "openai-beta" -> "api_beta";
                default ->
                    throw new IllegalStateException(
                        "Legacy custom headers require an explicitly supported transport contract");
              };
          var prior = transport.putIfAbsent(field, value);
          if (prior != null && !prior.equals(value))
            throw new IllegalStateException("Conflicting legacy account connection attributes");
        });
    }
    ChatEndpointCatalog.validateBaseUrl("legacy chat base-url", url);
    transport.put("request_timeout", timeout.toString());
    var endpoint =
        new ResolvedLlmEndpoint(
            previous.endpointId(),
            previous.generation(),
            previous.apiType(),
            url,
            previous.credentialRef(),
            model,
            previous.capabilities(),
            dev.mikoto2000.rei.modeloptions.ModelOptions.outputLimit(options),
            options.getTemperature(),
            previous.readTimeout());
    return new Current(
        new RunLlmConnectionFactory.LegacySettings(
            endpoint, RunNativeOptions.generation(options), keys.authentication(), transport),
        keys.value());
  }

  private Keys globalKeys(ResolvedConnectionProperties connection) {
    if (connection.getApiKey() != null) return explicit(connection.getApiKey());
    var ambient = environmentKey.get();
    if (ambient != null) return explicit(ambient);
    if (connection.getCredential() instanceof com.openai.credential.BearerTokenCredential bearer)
      return new Keys(LlmAuthentication.API_KEY, bearer::token);
    throw new IllegalStateException("Legacy chat credentials are unavailable");
  }

  private static Keys explicit(String value) {
    return value == null || value.isBlank()
        ? new Keys(LlmAuthentication.NONE, () -> null)
        : new Keys(LlmAuthentication.API_KEY, () -> value);
  }

  private static java.time.Duration nativeTimeout(
      org.springframework.ai.model.tool.ToolCallingChatOptions options) {
    if (options instanceof org.springframework.ai.openai.OpenAiChatOptions chat)
      return chat.getTimeout();
    if (options
        instanceof org.springframework.ai.openai.responses.OpenAiResponsesChatOptions responses)
      return responses.getTimeout();
    throw new IllegalStateException("Legacy chat API is unavailable");
  }

  private static IllegalStateException changed() {
    return new IllegalStateException("Legacy credential binding changed or is unavailable");
  }
}
