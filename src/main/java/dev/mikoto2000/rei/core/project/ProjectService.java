package dev.mikoto2000.rei.core.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.core.datasource.ReiPaths;

@Service
public class ProjectService {

  private static volatile ProjectService currentService;

  private final Path startupDirectory;
  private final Path projectsFile;
  private final AtomicReference<Path> currentProject;

  public ProjectService() {
    this(ReiPaths.startupDirectory(), ReiPaths.projectsFilePath());
  }

  public ProjectService(Path startupDirectory, Path projectsFile) {
    this.startupDirectory = normalize(startupDirectory);
    this.projectsFile = projectsFile;
    this.currentProject = new AtomicReference<>(this.startupDirectory);
    currentService = this;
  }

  public Path startupDirectory() {
    return startupDirectory;
  }

  public Path currentProject() {
    return currentProject.get();
  }

  public synchronized List<Path> list() {
    return loadProjects().stream().toList();
  }

  public synchronized Path add(String directory) {
    Path project = resolveDirectory(directory);
    if (!Files.isDirectory(project)) {
      throw new IllegalArgumentException("ディレクトリが存在しません: " + project);
    }
    LinkedHashSet<Path> projects = loadProjects();
    projects.add(project);
    saveProjects(projects);
    return project;
  }

  public synchronized Path remove(String directory) {
    Path project = resolveDirectory(directory);
    LinkedHashSet<Path> projects = loadProjects();
    boolean removed = projects.remove(project);
    projects.add(startupDirectory);
    if (removed) {
      saveProjects(projects);
    }
    if (currentProject.get().equals(project)) {
      currentProject.set(startupDirectory);
    }
    return project;
  }

  public synchronized Path cd(String directory) {
    Path project = resolveDirectory(directory);
    if (!loadProjects().contains(project)) {
      throw new IllegalArgumentException("未登録のプロジェクトです: " + project);
    }
    if (!Files.isDirectory(project)) {
      throw new IllegalArgumentException("ディレクトリが存在しません: " + project);
    }
    currentProject.set(project);
    return project;
  }

  public static List<String> registeredProjectPathStrings() {
    ProjectService service = currentService;
    if (service != null) {
      return service.list().stream().map(Path::toString).toList();
    }
    return loadProjectPathStrings(ReiPaths.startupDirectory(), ReiPaths.projectsFilePath());
  }

  public static Path currentProjectOrStartupDirectory() {
    ProjectService service = currentService;
    return service == null ? ReiPaths.startupDirectory() : service.currentProject();
  }

  static List<String> loadProjectPathStrings(Path startupDirectory, Path projectsFile) {
    LinkedHashSet<Path> projects = new LinkedHashSet<>();
    projects.add(normalize(startupDirectory));
    if (Files.isRegularFile(projectsFile)) {
      try {
        for (String line : Files.readAllLines(projectsFile)) {
          if (!line.isBlank()) {
            projects.add(normalize(Path.of(line.strip())));
          }
        }
      } catch (IOException e) {
        return projects.stream().map(Path::toString).toList();
      }
    }
    return projects.stream().map(Path::toString).toList();
  }

  private LinkedHashSet<Path> loadProjects() {
    LinkedHashSet<Path> projects = new LinkedHashSet<>();
    projects.add(startupDirectory);
    for (String path : loadProjectPathStrings(startupDirectory, projectsFile)) {
      projects.add(normalize(Path.of(path)));
    }
    return projects;
  }

  private void saveProjects(Set<Path> projects) {
    try {
      ReiPaths.ensureParentDirectoryExists(projectsFile);
      Files.write(projectsFile, projects.stream().map(Path::toString).toList());
    } catch (Exception e) {
      throw new IllegalStateException("プロジェクト一覧の保存に失敗しました", e);
    }
  }

  private Path resolveDirectory(String directory) {
    if (directory == null || directory.isBlank()) {
      throw new IllegalArgumentException("directory は空にできません");
    }
    Path path = Path.of(directory);
    if (!path.isAbsolute()) {
      path = currentProject.get().resolve(path);
    }
    return normalize(path);
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }
}
