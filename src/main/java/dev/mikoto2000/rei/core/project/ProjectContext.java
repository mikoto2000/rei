package dev.mikoto2000.rei.core.project;

import java.nio.file.Path;

/** Persistent identity is independent of its mutable filesystem location. */
public record ProjectContext(String id, String name, Path root) {
  public ProjectContext {
    java.util.UUID.fromString(id);
    root = root.toAbsolutePath().normalize();
  }
  public String conversationId(String logicalId) { return "project:" + id + ":" + logicalId; }
}
