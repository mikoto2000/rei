package dev.mikoto2000.rei.core.project;

import java.nio.file.Path;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;

public final class ProjectStorage {
  private ProjectStorage() {}
  public static Path directory(String projectId) {
    java.util.UUID.fromString(projectId);
    return ReiDataDirectory.current().resolve("projects").resolve(projectId);
  }
  public static String projectId(String conversationId) {
    if (conversationId != null && conversationId.startsWith("project:")) {
      int end = conversationId.indexOf(':', 8);
      if (end > 8) { String id = conversationId.substring(8, end); java.util.UUID.fromString(id); return id; }
    }
    return null;
  }
  public static Path currentDirectory() {
    var context = ProjectService.contextForOperation();
    return context == null ? ReiDataDirectory.current() : directory(context.id());
  }
}
