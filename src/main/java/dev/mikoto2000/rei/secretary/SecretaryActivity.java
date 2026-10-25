package dev.mikoto2000.rei.secretary;

import java.time.Instant;

/** Read-only, explicitly uncertain activity metadata; never screen text or screenshots. */
@FunctionalInterface
public interface SecretaryActivity {
  Snapshot snapshot(String project, Instant now);

  record Snapshot(String state, Instant observedAt, boolean focused, boolean busy) {}
}
