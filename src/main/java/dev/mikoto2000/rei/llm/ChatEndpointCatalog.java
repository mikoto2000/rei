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

  public ChatEndpointCatalog(LlmProperties properties) {
    var features = properties.getFeatures();
    features.forEach((feature, server) -> {
      if (!LlmFeature.CHAT.equals(feature) && server != null) {
        if (server.getActive() != null)
          throw invalid(PREFIX + feature + ".active", "dynamic selection is only supported for chat");
        if (server.getEndpoints() != null)
          throw invalid(PREFIX + feature + ".endpoints", "named profiles are only supported for chat");
      }
    });
    var chat = features.get(LlmFeature.CHAT);
    var endpoints = chat == null ? null : chat.getEndpoints();
    if (endpoints == null) {
      if (chat != null && chat.getActive() != null)
        throw invalid(CHAT + ".endpoints", "must define the named profiles selected by active");
      initial = "default";
      profiles = List.of(new Profile("default", chat == null ? null : chat.getModel()));
    } else {
      if (endpoints.isEmpty()) throw invalid(CHAT + ".endpoints", "must not be empty");
      for (var name : endpoints.keySet())
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
          throw invalid(CHAT + ".endpoints", "names must match [A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
      if (chat.getActive() == null || !endpoints.containsKey(chat.getActive()))
        throw invalid(CHAT + ".active", "must name a registered chat endpoint");
      endpoints.forEach((name, server) -> validate(CHAT + ".endpoints." + name, server));
      initial = chat.getActive();
      profiles = endpoints.entrySet().stream().sorted(Map.Entry.comparingByKey())
          .map(entry -> new Profile(entry.getKey(), entry.getValue().getModel())).toList();
    }
  }

  private static void validate(String field, LlmProperties.Server server) {
    if (server == null) throw invalid(field, "must define a connection");
    if (server.getActive() != null)
      throw invalid(field + ".active", "nested selection is not supported");
    if (server.getEndpoints() != null)
      throw invalid(field + ".endpoints", "nested profiles are not supported");
    if (server.getModel() == null || server.getModel().isBlank())
      throw invalid(field + ".model", "must not be blank for a named profile");
    if (server.isFallbackToDefault())
      throw invalid(field + ".fallback-to-default", "must be false; named profiles cannot fall back");
    validateBaseUrl(field + ".base-url", server.getBaseUrl());
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
    return profiles;
  }

  public record Profile(String id, String model) {}
}
