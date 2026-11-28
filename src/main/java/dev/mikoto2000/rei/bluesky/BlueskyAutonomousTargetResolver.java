package dev.mikoto2000.rei.bluesky;

import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousTargetResolver;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Reads the configured public handle without authenticating or acquiring publication authority. */
@Component
@ConditionalOnProperty(
    prefix = "rei.autonomous-action",
    name = {"enabled", "bluesky.enabled"},
    havingValue = "true")
public final class BlueskyAutonomousTargetResolver implements AutonomousTargetResolver {
  private final Supplier<String> handle;
  private final BlueskyApiClient api;

  public BlueskyAutonomousTargetResolver(BlueskyProperties settings, BlueskyApiClient api) {
    this.handle = settings::getHandle;
    this.api = api;
  }

  public AutonomousDraft.Target resolve() {
    String handle = this.handle.get();
    if (handle == null || handle.isBlank()) return null;
    return new AutonomousDraft.Target(api.resolveHandle(handle), handle);
  }
}
