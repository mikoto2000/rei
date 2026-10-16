package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotHumanServiceTest {
  @TempDir Path root;
  DotDelegationService service;
  DotRequestRepository repo;
  ProjectService projects;
  ProjectContext project;

  @BeforeEach
  void setup() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, Clock.systemUTC());
    project =
        new ProjectContext(
            UUID.randomUUID().toString(), "p", Files.createDirectory(root.resolve("project")));
    projects = mock(ProjectService.class);
    when(projects.registeredProjects()).thenReturn(List.of(project));
    when(projects.currentContext()).thenReturn(project);
    var properties =
        new DotProperties(true, false, false, Set.of(project.id()), null, 0, 0, null, null, 0, 0);
    service = new DotDelegationService(repo, properties, projects, mock(SessionRepository.class));
  }

  @Test
  void explicitHumanRequestIsSanitizedAndCancelledThroughShell() {
    var command = new picocli.CommandLine(new DotCommand(service, projects));
    var output = new java.io.StringWriter();
    command.setOut(new java.io.PrintWriter(output));
    assertEquals(
        0,
        command.execute(
            "request",
            "Review C:\\private\\design.txt token=hidden",
            "--context",
            "/home/private/secret",
            "--category",
            "REVIEW"));
    assertFalse(output.toString().contains("hidden"));
    assertFalse(output.toString().contains("C:\\private"));
    assertFalse(output.toString().contains("/home/private"));
    var list = service.list(project.id());
    assertEquals(1, list.size());
    String id = (String) list.getFirst().get("request_id");
    assertEquals("CANCELLED", service.cancel(project.id(), id).get("status"));
  }

  @Test
  void adminProjectCategoryAndSessionBoundariesAreChecked() {
    assertThrows(
        DotException.class,
        () -> service.createHuman("other", null, Map.of("instruction", "task")));
    assertThrows(
        DotException.class,
        () -> service.createHuman(project.id(), "missing", Map.of("instruction", "task")));
    assertThrows(
        DotException.class,
        () ->
            service.createHuman(
                project.id(), null, Map.of("instruction", "task", "category", "EXECUTE_SHELL")));
  }
}
