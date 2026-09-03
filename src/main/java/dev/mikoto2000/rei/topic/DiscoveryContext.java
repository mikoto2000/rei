package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

public record DiscoveryContext(List<String> seeds, Instant currentTime) {
  public DiscoveryContext {
    seeds = seeds == null ? List.of() : List.copyOf(seeds);
    if (currentTime == null) throw new IllegalArgumentException("currentTime must not be null");
  }
}
