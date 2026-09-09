package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.chat.AgentRunScope;

@Service
public class ProjectService {
  private static volatile ProjectService currentService;
  private final Path startupDirectory;
  private final ProjectRegistry registry;
  private final AtomicReference<Path> currentProject;
  private final boolean scoped;

  public ProjectService() { this(ReiPaths.startupDirectory(), new ProjectRegistry(ReiPaths.projectsFilePath())); }
  /** Compatibility constructor for callers that only use path management. */
  public ProjectService(Path startup, Path file) { this(startup, new ProjectRegistry(file), false); }
  public ProjectService(Path startup, ProjectRegistry registry) { this(startup, registry, true); }
  private ProjectService(Path startup, ProjectRegistry registry, boolean scoped) {
    this.startupDirectory = startup.toAbsolutePath().normalize();
    this.registry = registry;
    this.currentProject = new AtomicReference<>(this.startupDirectory);
    this.scoped = scoped;
    currentService = this;
  }
  public Path startupDirectory() { return startupDirectory; }
  public Path currentProject() { return currentProject.get(); }
  public ProjectContext currentContext() { return registry.resolve(currentProject()); }
  public List<ProjectContext> registeredProjects() { return registry.list(); }
  public List<Path> list() {
    var paths = new LinkedHashSet<Path>();
    paths.add(startupDirectory);
    registry.list().forEach(p -> paths.add(p.root()));
    return List.copyOf(paths);
  }
  public Path add(String directory) { return registry.resolve(resolveDirectory(directory)).root(); }
  public Path cd(String directory) {
    var context = registry.resolve(resolveDirectory(directory));
    currentProject.set(context.root());
    return context.root();
  }
  public Path remove(String directory) {
    Path path = resolveDirectory(directory);
    registry.remove(path);
    if (currentProject.get().equals(path)) currentProject.set(startupDirectory);
    return path;
  }
  public static ProjectContext contextForOperation() {
    var run = AgentRunScope.current();
    if (run != null && run.projectId() != null) return new ProjectContext(run.projectId(),
        run.projectRoot().getFileName().toString(), run.projectRoot());
    if (run != null) return null;
    ProjectService service = currentService;
    return service != null && service.scoped && Files.isDirectory(service.currentProject()) ? service.currentContext() : null;
  }
  public static List<String> registeredProjectPathStrings() {
    var service = currentService;
    return service == null ? List.of(ReiPaths.startupDirectory().toString()) : service.list().stream().map(Path::toString).toList();
  }
  public static Path currentProjectOrStartupDirectory() {
    var service = currentService;
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
    if (directory == null || directory.isBlank()) throw new IllegalArgumentException("directory must not be blank");
    Path path = Path.of(directory);
    return (path.isAbsolute() ? path : currentProject().resolve(path)).toAbsolutePath().normalize();
  }
}
