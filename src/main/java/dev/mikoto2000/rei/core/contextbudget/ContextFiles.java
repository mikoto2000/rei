package dev.mikoto2000.rei.core.contextbudget;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import dev.mikoto2000.rei.core.project.ProjectStorage;

final class ContextFiles {
  static Path directory(Path base, String conversation) {
    String project = ProjectStorage.projectId(conversation);
    return (project == null ? base : base.resolve("projects").resolve(project)).resolve("state/context");
  }
  static String key(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
  static void write(Path target, String content) {
    Path temporary = null;
    try {
      Files.createDirectories(target.getParent());
      temporary = Files.createTempFile(target.getParent(), "context-", ".tmp");
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException error) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    } catch (java.io.IOException error) { throw new IllegalStateException("Cannot persist context state", error); }
    finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { } }
  }
}
