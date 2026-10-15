package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.project.*;
import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResumeOverviewServiceTest {
  @Test
  void registeredScopeAndFingerprintAreStableWithoutAnyResumeOrReconciliation() {
    var repository = mock(PersistentCheckpointRepository.class);
    var projects = mock(ProjectService.class);
    var project = mock(ProjectContext.class);
    when(project.id()).thenReturn("A");
    when(project.name()).thenReturn("A");
    when(projects.registeredProjects()).thenReturn(List.of(project));
    var candidate =
        new ResumeOverview.Candidate(
            "task", 1, 1, Instant.EPOCH, "INTERRUPTED", "request", "未記録", "test", "要照合", false);
    when(repository.overviewSelection(eq("A"), any()))
        .thenReturn(new PersistentCheckpointRepository.OverviewSelection(2, candidate));
    var properties = new ResumeOverviewProperties();
    var now = Instant.parse("2026-10-13T00:00:00Z");
    var service =
        new ResumeOverviewService(
            repository, projects, properties, Clock.fixed(now, ZoneOffset.UTC));
    assertEquals(service.overview("A").fingerprint(), service.overview("A").fingerprint());
    verify(repository, times(2)).overviewSelection("A", now.minus(Duration.ofDays(30)));
    verifyNoMoreInteractions(repository);
    assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () -> service.overview("B"));
    properties.setEnabled(false);
    assertFalse(service.enabled());
    assertEquals(2, service.overview("A").count());
  }

  @Test
  void overviewCommandForcesDisplayAndExistingCommandsKeepTheirRoutes() {
    var service = mock(PersistentCheckpointService.class);
    var projects = mock(ProjectService.class);
    var overview = mock(ResumeOverviewService.class);
    var project = mock(ProjectContext.class);
    when(project.id()).thenReturn("A");
    when(project.name()).thenReturn("A");
    when(projects.currentContext()).thenReturn(project);
    var candidate =
        new ResumeOverview.Candidate(
            "task", 1, 1, Instant.EPOCH, "INTERRUPTED", "request", "未記録", "test", "要照合", false);
    when(overview.overview("A")).thenReturn(new ResumeOverview("A", "A", 1, candidate, "f"));
    when(overview.zone(any())).thenReturn(ZoneOffset.UTC);
    var resume = new ResumeCommand(service, projects);
    resume.setOverview(overview);
    var output = new StringWriter();
    var command = new picocli.CommandLine(resume).setOut(new PrintWriter(output));
    assertEquals(0, command.execute("overview"));
    assertEquals(0, command.execute("overview"));
    assertEquals(2, output.toString().split("最新の再開候補", -1).length - 1);
    verifyNoInteractions(service);
    when(service.list("A")).thenReturn(List.of());
    assertEquals(0, command.execute("list"));
    verify(service).list("A");
    assertEquals(0, command.execute("show", "task"));
    verify(service).get("A", "task");
    verify(service).inspect("A", "task");
    assertEquals(0, command.execute("abandon", "task"));
    verify(service).abandon("A", "task");
    assertEquals(0, command.execute("task"));
    verify(service)
        .resume("A", "task", dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL);
  }
}
