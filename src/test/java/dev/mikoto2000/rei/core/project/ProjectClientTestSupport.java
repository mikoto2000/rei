package dev.mikoto2000.rei.core.project;

/** Gives existing single-client tests an explicit session with automatic cleanup. */
public abstract class ProjectClientTestSupport {
  private final java.util.Deque<ProjectClientScope> scopes = new java.util.ArrayDeque<>();

  protected ProjectService connect(ProjectService service) {
    scopes.push(service.newClient().open());
    return service;
  }

  @org.junit.jupiter.api.AfterEach
  void closeProjectClients() {
    while (!scopes.isEmpty()) scopes.pop().close();
  }
}
