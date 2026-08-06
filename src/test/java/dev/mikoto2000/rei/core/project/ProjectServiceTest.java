package dev.mikoto2000.rei.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void startsWithStartupDirectoryAsCurrentProject() {
    ProjectService service = newService();

    assertEquals(tempDir.toAbsolutePath().normalize(), service.currentProject());
    assertTrue(service.list().contains(tempDir.toAbsolutePath().normalize()));
  }

  @Test
  void addRegistersNormalizedDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();

    Path added = service.add("project-a");

    assertEquals(project.toAbsolutePath().normalize(), added);
    assertTrue(service.list().contains(project.toAbsolutePath().normalize()));
  }

  @Test
  void cdChangesCurrentProjectOnlyForRegisteredDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();
    service.add(project.toString());

    service.cd(project.toString());

    assertEquals(project.toAbsolutePath().normalize(), service.currentProject());
  }

  @Test
  void cdRejectsUnregisteredDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();

    assertThrows(IllegalArgumentException.class, () -> service.cd(project.toString()));
  }

  @Test
  void removeCurrentProjectReturnsToStartupDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();
    service.add(project.toString());
    service.cd(project.toString());

    service.remove(project.toString());

    assertEquals(tempDir.toAbsolutePath().normalize(), service.currentProject());
    assertTrue(!service.list().contains(project.toAbsolutePath().normalize()));
  }

  private ProjectService newService() {
    return new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects"));
  }
}
