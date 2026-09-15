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
      Path input = Path.of(target);
      Path requested = input.isAbsolute() ? input : root.resolve(input);
      // Check the actual location, including project paths reached through links/junctions.
      Path canonical = requested.toRealPath();
      if (!canonical.startsWith(root)) throw new IllegalArgumentException("Target must be inside the current project");
      return canonical;
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException("Target path is invalid; use an absolute path or a path relative to the current project");
    } catch (NoSuchFileException error) {
      throw new IllegalArgumentException("Current project or target does not exist");
    } catch (IOException error) {
      throw new IllegalArgumentException("Current project or target is unavailable");
    }
  }
}
