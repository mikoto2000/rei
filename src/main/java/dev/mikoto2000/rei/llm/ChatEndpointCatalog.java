package dev.mikoto2000.rei.llm;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Server-side connection metadata; credentials never belong in public profile descriptions. */
@Component
public final class ChatEndpointCatalog {
  private static final String PREFIX = "rei.llm.features.";
  private static final String CHAT = PREFIX + LlmFeature.CHAT;
  private final List<Profile> profiles;
  private final String initial;
  private final Map<String, LlmProperties.Server> connections;
  private final Map<String, LlmProperties.Server> featureSettings;
  private final List<String> diagnostics;

  public ChatEndpointCatalog(LlmProperties properties) {
    var features = properties.getFeatures();
    features.forEach((feature, server) -> {
      if (!LlmFeature.CHAT.equals(feature) && server != null) {
        if (server.getActive() != null)
          throw invalid(
              PREFIX + feature + ".active", "dynamic selection is only supported for chat");
        if (server.getEndpoints() != null)
          throw invalid(
              PREFIX + feature + ".endpoints", "named profiles are only supported for chat");
        if (provider(server, PREFIX + feature) == LlmProvider.CHATGPT_PLAN)
          throw invalid(
              PREFIX + feature + ".provider",
              "chatgpt-plan is only supported by named chat profiles");
        if (server.getAccountRef() != null)
          throw invalid(
              PREFIX + feature + ".account-ref",
              "ChatGPT accounts are not supported by this inference purpose");
      }
    });
    var chat = features.get(LlmFeature.CHAT);
    var endpoints = chat == null ? null : chat.getEndpoints();
    if (endpoints == null) {
      if (chat != null && chat.getActive() != null)
        throw invalid(CHAT + ".endpoints", "must define the named profiles selected by active");
      if (chat != null && provider(chat, CHAT) == LlmProvider.CHATGPT_PLAN)
        throw invalid(CHAT + ".provider", "chatgpt-plan requires a named endpoint");
      if (chat != null && chat.getAccountRef() != null)
        throw invalid(CHAT + ".account-ref", "is only supported for chatgpt-plan named endpoints");
      initial = "default";
      profiles = List.of(profile("default", chat, CHAT, properties.getMaxTools()));
    } else {
      if (endpoints.isEmpty()) throw invalid(CHAT + ".endpoints", "must not be empty");
      for (var name : endpoints.keySet())
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
          throw invalid(CHAT + ".endpoints", "names must match [A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
      if (chat.getActive() == null || !endpoints.containsKey(chat.getActive()))
        throw invalid(CHAT + ".active", "must name a registered chat endpoint");
      endpoints.forEach((name, server) -> validate(CHAT + ".endpoints." + name, server));
      initial = chat.getActive();
      profiles =
          endpoints.entrySet().stream()
              .sorted(Map.Entry.comparingByKey())
              .map(entry ->
                  profile(
                      entry.getKey(),
                      entry.getValue(),
                      CHAT + ".endpoints." + entry.getKey(),
                      properties.getMaxTools()))
              .toList();
    }
    var resolved = new java.util.LinkedHashMap<String, LlmProperties.Server>();
    if (endpoints == null) resolved.put("default", canonicalConnection(chat, CHAT));
    else
      endpoints.forEach((name, server) ->
          resolved.put(name, canonicalConnection(server, CHAT + ".endpoints." + name)));
    connections = java.util.Collections.unmodifiableMap(resolved);
    var staticFeatures = new java.util.LinkedHashMap<>(features);
    if (endpoints != null) {
      var auxiliaryChat = chat.copy();
      auxiliaryChat.setActive(null);
      auxiliaryChat.setEndpoints(null);
      auxiliaryChat.setProvider(null);
      auxiliaryChat.setAccountRef(null);
      staticFeatures.put(LlmFeature.CHAT, auxiliaryChat);
      diagnostics = legacyDiagnostics(chat);
    } else diagnostics = List.of();
    featureSettings = java.util.Collections.unmodifiableMap(staticFeatures);
  }

  private static LlmProperties.Server canonicalConnection(
      LlmProperties.Server source, String field) {
    var copy = source == null ? new LlmProperties.Server() : source.copy();
    var provider = provider(copy, field);
    copy.setProvider(provider.id());
    copy.setActive(null);
    copy.setEndpoints(null);
    if (provider == LlmProvider.CHATGPT_PLAN) {
      copy.setBaseUrl(dev.mikoto2000.rei.chatgpt.PlanOAuth.RESOURCE);
      copy.setApiKey(null);
      copy.setApi(LlmApiType.RESPONSES);
      if (copy.getReasoningReplay() == null) copy.setReasoningReplay(ReasoningReplayMode.ENCRYPTED);
    } else {
      copy.setBaseUrl(OpenAiCompatibleEndpoint.baseUrl(copy.getBaseUrl()));
      if (copy.getApi() == null) copy.setApi(LlmApiType.CHAT_COMPLETIONS);
    }
    return copy;
  }

  private static List<String> legacyDiagnostics(LlmProperties.Server chat) {
    var result = new java.util.ArrayList<String>();
    var fields = new java.util.LinkedHashMap<String, Boolean>();
    fields.put("base-url", chat.getBaseUrl() != null && !chat.getBaseUrl().isBlank());
    fields.put("api-key", chat.getApiKey() != null && !chat.getApiKey().isBlank());
    fields.put("model", chat.getModel() != null && !chat.getModel().isBlank());
    fields.put("api", chat.getApi() != null);
    fields.put("provider", chat.getProvider() != null);
    fields.put("account-ref", chat.getAccountRef() != null);
    fields.put("usage-path", chat.getUsagePath() != null);
    fields.put("model-capabilities", !chat.getModelCapabilities().isEmpty());
    fields.put("temperature", chat.getTemperature() != null);
    fields.put("max-output-tokens", chat.getMaxOutputTokens() != null);
    fields.put("max-tools", chat.getMaxTools() != null);
    fields.put("reasoning-replay", chat.getReasoningReplay() != null);
    fields.put("supported-settings", !chat.getSupportedSettings().isEmpty());
    fields.put("fallback-to-default", chat.isFallbackToDefault());
    fields.put("endpoint-generation", chat.getEndpointGeneration() != 1);
    fields.forEach((field, configured) -> {
      if (configured) result.add(CHAT + "." + field + ": not applied to named chat profiles");
    });
    result.add(CHAT + ".active: auxiliary inference retains static feature/default configuration");
    return List.copyOf(result);
  }

  private static void validate(String field, LlmProperties.Server server) {
    if (server == null) throw invalid(field, "must define a connection");
    if (server.getActive() != null)
      throw invalid(field + ".active", "nested selection is not supported");
    if (server.getEndpoints() != null)
      throw invalid(field + ".endpoints", "nested profiles are not supported");
    if (server.getModel() == null || server.getModel().isBlank())
      throw invalid(field + ".model", "must not be blank for a named profile");
    requireNoControlCharacters(field + ".model", server.getModel());
    if (server.isFallbackToDefault())
      throw invalid(
          field + ".fallback-to-default", "must be false; named profiles cannot fall back");
    if (provider(server, field) == LlmProvider.CHATGPT_PLAN) {
      if (server.getBaseUrl() != null)
        throw invalid(field + ".base-url", "is not allowed for chatgpt-plan");
      if (server.getApiKey() != null)
        throw invalid(field + ".api-key", "is not allowed for chatgpt-plan");
      if (server.getApi() != LlmApiType.RESPONSES)
        throw invalid(field + ".api", "must be responses for chatgpt-plan");
      if (server.getAccountRef() == null || !server.getAccountRef().matches("[a-zA-Z0-9_-]{1,64}"))
        throw invalid(field + ".account-ref", "must name a valid ChatGPT account label");
      if (server.getReasoningReplay() != null
          && server.getReasoningReplay() != ReasoningReplayMode.ENCRYPTED)
        throw invalid(field + ".reasoning-replay", "must be encrypted for chatgpt-plan");
    } else {
      if (server.getAccountRef() != null)
        throw invalid(field + ".account-ref", "is only supported for chatgpt-plan");
      requireNoControlCharacters(field + ".api-key", server.getApiKey());
      validateBaseUrl(field + ".base-url", server.getBaseUrl());
    }
  }

  private static void requireNoControlCharacters(String field, String value) {
    if (value != null && value.codePoints().anyMatch(Character::isISOControl))
      throw invalid(field, "must not contain control characters");
  }

  private static LlmProvider provider(LlmProperties.Server server, String field) {
    return LlmProvider.configured(
        server == null ? null : server.getProvider(), field + ".provider");
  }

  private static Profile profile(
      String name, LlmProperties.Server server, String field, Integer defaultMaxTools) {
    var provider = provider(server, field);
    var api =
        server == null || server.getApi() == null ? LlmApiType.CHAT_COMPLETIONS : server.getApi();
    return new Profile(
        name,
        server == null ? null : server.getModel(),
        provider,
        api,
        server == null ? null : server.getAccountRef(),
        server == null ? 1 : server.getEndpointGeneration(),
        LlmUsagePath.configured(
            server == null ? null : server.getUsagePath(), provider, field + ".usage-path"),
        LlmEndpointCapabilities.resolve(server, provider, api, defaultMaxTools, field));
  }

  private static void validateBaseUrl(String field, String value) {
    if (value != null && !value.isBlank()) {
      try {
        var uri = new java.net.URI(value.strip());
        var scheme = uri.getScheme();
        if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            && uri.getHost() != null
            && uri.getRawUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)) return;
      } catch (java.net.URISyntaxException invalid) {
        // A parser's exception contains the original URL, which can contain credentials.
      }
    }
    throw invalid(field, "must be an absolute http(s) URL without user info, query, or fragment");
  }

  private static IllegalArgumentException invalid(String field, String reason) {
    return new IllegalArgumentException(field + ": " + reason);
  }

  public String initialEndpoint() {
    return initial;
  }

  public List<Profile> profiles() {
    return List.copyOf(profiles);
  }

  LlmProperties.Server connection(String name) {
    var value = name == null ? null : connections.get(name);
    if (value == null) throw invalid("endpoint-name", "must name a registered chat endpoint");
    return value.copy();
  }

  public LlmProperties.Server feature(String feature) {
    var value = featureSettings.get(feature);
    return value == null ? null : value.copy();
  }

  public List<String> diagnostics() {
    return List.copyOf(diagnostics);
  }

  public record Profile(
      String id,
      String model,
      LlmProvider provider,
      LlmApiType api,
      String accountRef,
      long generation,
      LlmUsagePath usagePath,
      LlmEndpointCapabilities capabilities) {}
}
