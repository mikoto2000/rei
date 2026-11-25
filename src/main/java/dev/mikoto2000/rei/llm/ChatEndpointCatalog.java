package dev.mikoto2000.rei.llm;

import java.util.List;

/** Server-side connection metadata; credentials never belong in public profile descriptions. */
public final class ChatEndpointCatalog {
  private final Profile legacy;

  public ChatEndpointCatalog(LlmProperties properties) {
    var chat = properties.feature(LlmFeature.CHAT);
    legacy = new Profile("default", chat == null ? null : chat.getModel());
  }

  public String initialEndpoint() {
    return "default";
  }

  public List<Profile> profiles() {
    return List.of(legacy);
  }

  public record Profile(String id, String model) {}
}
