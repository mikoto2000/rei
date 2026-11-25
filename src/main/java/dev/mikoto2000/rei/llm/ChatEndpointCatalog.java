package dev.mikoto2000.rei.llm;

import java.util.List;
import java.util.Map;

/** Server-side connection metadata; credentials never belong in public profile descriptions. */
public final class ChatEndpointCatalog {
  private final List<Profile> profiles;
  private final String initial;

  public ChatEndpointCatalog(LlmProperties properties) {
    var chat = properties.feature(LlmFeature.CHAT);
    var endpoints = chat == null ? null : chat.getEndpoints();
    if (endpoints == null) {
      initial = "default";
      profiles = List.of(new Profile("default", chat == null ? null : chat.getModel()));
    } else {
      initial = chat.getActive();
      profiles = endpoints.entrySet().stream().sorted(Map.Entry.comparingByKey())
          .map(entry -> new Profile(entry.getKey(), entry.getValue().getModel())).toList();
    }
  }

  public String initialEndpoint() {
    return initial;
  }

  public List<Profile> profiles() {
    return profiles;
  }

  public record Profile(String id, String model) {}
}
