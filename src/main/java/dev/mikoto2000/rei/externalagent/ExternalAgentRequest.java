package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.io.IOException;

public record ExternalAgentRequest(Agent agent, Action action, String task, Path projectRoot,
    Path target, String context, String runId, String delegationId) {
  public enum Agent { CODEX }
  public enum Action { REVIEW }
  public static Path resolveTarget(Path projectRoot, String target) {
    try {
      Path root = projectRoot.toRealPath();
      if (!Files.isDirectory(root)) throw new IllegalArgumentException("Current project is not a directory");
      if (target == null || target.isBlank()) return null;
      Path requested = root.resolve(target).normalize();
      if (!requested.startsWith(root)) throw new IllegalArgumentException("Target must be inside the current project");
      Path canonical = requested.toRealPath();
      if (!canonical.startsWith(root)) throw new IllegalArgumentException("Target must be inside the current project");
      return canonical;
    } catch (IOException | InvalidPathException error) {
      throw new IllegalArgumentException("Current project or target is unavailable");
    }
  }
}
