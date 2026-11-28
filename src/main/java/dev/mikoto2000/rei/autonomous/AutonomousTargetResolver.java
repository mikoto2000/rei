package dev.mikoto2000.rei.autonomous;

import dev.mikoto2000.rei.bluesky.BlueskyApiClient;
import dev.mikoto2000.rei.bluesky.BlueskyProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reads the configured public handle without authenticating or acquiring publication authority. */
@Component
@ConditionalOnProperty(
    prefix = "rei.autonomous-action",
    name = {"enabled", "bluesky.enabled"},
    havingValue = "true")
public final class AutonomousTargetResolver {
  private final BlueskyProperties settings;
  private final BlueskyApiClient api;

  public AutonomousTargetResolver(BlueskyProperties settings, BlueskyApiClient api) {
    this.settings = settings;
    this.api = api;
  }

  public AutonomousDraft.Target resolve() {
    String handle = settings.getHandle();
    if (handle == null || handle.isBlank()) return null;
    return new AutonomousDraft.Target(api.resolveHandle(handle), handle);
  }
}
