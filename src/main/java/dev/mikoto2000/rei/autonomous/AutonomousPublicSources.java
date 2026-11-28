package dev.mikoto2000.rei.autonomous;

import java.time.Instant;
import java.util.List;

/** Explicit topic consent and a separate public-only projection for generation. */
public interface AutonomousPublicSources {
  record Source(
      String id,
      int revision,
      String text,
      boolean publicUseAllowed,
      Instant updatedAt,
      String actor) {}

  record PublicSource(String id, String text) {}

  Source register(String text, boolean publicUseAllowed, String actor);

  Source get(String id);

  List<Source> list(int limit);

  Source version(String id, int revision);

  Source edit(String id, int expectedRevision, String text, String actor);

  Source permission(String id, int expectedRevision, boolean allowed, String actor);

  List<PublicSource> publicSnapshot();
}
