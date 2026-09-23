package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.chat.AgentRunScope;

@Service
public class ProjectService {
  private final Path startupDirectory;
  private final ProjectRegistry registry;
  private final boolean scoped;

  public ProjectService() { this(ReiPaths.startupDirectory(), new ProjectRegistry(ReiPaths.projectsFilePath())); }
  /** Compatibility constructor for callers that only use path management. */
  public ProjectService(Path startup, Path file) { this(startup, new ProjectRegistry(file), false); }
  public ProjectService(Path startup, ProjectRegistry registry) { this(startup, registry, true); }
  private ProjectService(Path startup, ProjectRegistry registry, boolean scoped) {
    this.startupDirectory = startup.toAbsolutePath().normalize();
    this.registry = registry;
    this.scoped = scoped;
  }
  public Path startupDirectory() { return startupDirectory; }
  public ProjectClient newClient() { return new ProjectClient(this, startupDirectory); }
  public ProjectClient currentClient() { return client(); }
  public String currentSessionId() { synchronized (client()) { return client().sessionId; } }
  public void selectSession(String sessionId) { synchronized (client()) { client().sessionId = sessionId; } }
  public static String selectedShellSession() {
    var client = ProjectClientScope.current();
    if (client == null) return null;
    synchronized (client) { return client.sessionId; }
  }
  private ProjectClient client() {
    var client = ProjectClientScope.current();
    if (client == null || client.service != this)
      throw new IllegalStateException("A project client scope is required");
    return client;
  }
  /** Client selection for interactive controls; execution ownership is contextForOperation(). */
  public Path currentProject() {
    var client = ProjectClientScope.current();
    return client == null ? startupDirectory : client().selection.get();
  }
  public ProjectContext currentContext() { return registry.resolve(currentProject()); }
  public List<ProjectContext> registeredProjects() { return registry.list(); }
  public List<ProjectContext> completionProjects() { return registry.completionSnapshot(); }
  public static List<String> completionProjectPathStrings() {
    var client = ProjectClientScope.current();
    if (client == null) return List.of(ReiPaths.startupDirectory().toString());
    var paths = new LinkedHashSet<String>();
    paths.add(client.service.startupDirectory.toString());
    client.service.completionProjects().forEach(project -> paths.add(project.root().toString()));
    return List.copyOf(paths);
  }
  public List<Path> list() {
    var paths = new LinkedHashSet<Path>();
    paths.add(startupDirectory);
    registry.list().forEach(p -> paths.add(p.root()));
    return List.copyOf(paths);
  }
  public Path add(String directory) { return registry.resolve(resolveDirectory(directory)).root(); }
  public Path cd(String directory) {
    var client = client();
    var context = registry.resolve(resolveDirectory(directory));
    synchronized (client) {
      if (!client.selection.get().equals(context.root())) client.sessionId = null;
      client.selection.set(context.root());
    }
    return context.root();
  }
  public Path remove(String directory) {
    var client = client();
    Path path = resolveDirectory(directory);
    registry.remove(path);
    synchronized (client) {
      if (client.selection.get().equals(path)) { client.selection.set(startupDirectory); client.sessionId = null; }
    }
    return path;
  }
  /** Running work keeps its captured ownership even when the client changes its selection. */
  public static ProjectContext contextForOperation() {
    var execution=dev.mikoto2000.rei.core.execution.ExecutionScope.current();
    if(execution!=null) return new ProjectContext(execution.projectId(),execution.projectRoot().getFileName().toString(),execution.projectRoot());
    var run = AgentRunScope.current();
    if (run != null && run.projectId() != null) return new ProjectContext(run.projectId(),
        run.projectRoot().getFileName().toString(), run.projectRoot());
    if (run != null) return null;
    var client = ProjectClientScope.current();
    ProjectService service = client == null ? null : client.service;
    return service != null && service.scoped && Files.isDirectory(service.currentProject()) ? service.currentContext() : null;
  }
  public static List<String> registeredProjectPathStrings() {
    var client = ProjectClientScope.current();
    var service = client == null ? null : client.service;
    return service == null ? List.of(ReiPaths.startupDirectory().toString()) : service.list().stream().map(Path::toString).toList();
  }
  public static Path currentProjectOrStartupDirectory() {
    var client = ProjectClientScope.current();
    var service = client == null ? null : client.service;
    return service == null ? ReiPaths.startupDirectory() : service.currentProject();
  }
  static List<String> loadProjectPathStrings(Path startup, Path file) {
    var paths = new LinkedHashSet<String>(); paths.add(startup.toAbsolutePath().normalize().toString());
    if (Files.isRegularFile(file)) {
      try {
        if (Files.readString(file).stripLeading().startsWith("{")) new ProjectRegistry(file).list().forEach(p -> paths.add(p.root().toString()));
        else Files.readAllLines(file).stream().filter(s -> !s.isBlank()).forEach(s -> paths.add(Path.of(s.strip()).toAbsolutePath().normalize().toString()));
      } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read project list", e); }
    }
    return List.copyOf(paths);
  }
  private Path resolveDirectory(String directory) {
    return resolveDirectory(currentProject(), directory);
  }
  /** Read-only validation, shared with registry selection, before application startup. */
  public static Path resolveExistingDirectory(Path base, String directory) {
    return ProjectRegistry.canonical(resolveDirectory(base, directory));
  }
  private static Path resolveDirectory(Path base, String directory) {
    if (directory == null || directory.isBlank()) throw new IllegalArgumentException("directory must not be blank");
    Path path = Path.of(directory);
    return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
  }
}
