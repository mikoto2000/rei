package dev.mikoto2000.rei.core.project;

import java.util.Objects;

/** Request adapter; bind explicitly on each worker and always close with try-with-resources. */
public final class ProjectClientScope implements AutoCloseable {
  private static final ThreadLocal<ProjectClient> CURRENT = new ThreadLocal<>();
  private final ProjectClient previous;

  private ProjectClientScope(ProjectClient client) {
    previous = CURRENT.get();
    CURRENT.set(Objects.requireNonNull(client));
  }

  public static ProjectClientScope open(ProjectClient client) { return new ProjectClientScope(client); }
  static ProjectClient current() { return CURRENT.get(); }
  @Override public void close() {
    if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
  }
}
