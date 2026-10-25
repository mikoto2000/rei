package dev.mikoto2000.rei.secretary;

import java.util.Optional;

/** Read-only access to already-observed dependency facts. Never performs a remote probe. */
public interface SecretaryDependencies {
  record Fact(String state, long revision) {}

  Optional<Fact> fact(String project, String id);
}
