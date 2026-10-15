package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;

class ResumeOverviewShellTest extends ProjectClientTestSupport {
  @TempDir Path directory;

  @Test
  void startupRestoreSwitchAndFailureUseShellOutput() throws Exception {
    var projects =
        connect(
            new ProjectService(directory, new ProjectRegistry(directory.resolve("projects.json"))));
    var a = projects.currentContext();
    var bPath = Files.createDirectory(directory.resolve("B"));
    projects.add(bPath.toString());
    var b =
        projects.registeredProjects().stream()
            .filter(p -> p.root().equals(bPath))
            .findFirst()
            .orElseThrow();
    var service = mock(ResumeOverviewService.class);
    when(service.enabled()).thenReturn(true);
    when(service.zone(any())).thenReturn(ZoneOffset.UTC);
    when(service.startupSummary()).thenReturn("前回からの再開候補:\n  A  2 件");
    var ca =
        new ResumeOverview.Candidate(
            "task-a", 1, 1, Instant.EPOCH, "INTERRUPTED", "request-a", "未記録", "test", "要照合", false);
    when(service.overview(a.id())).thenReturn(new ResumeOverview(a.id(), "A", 2, ca, "a1"));
    when(service.overview(b.id())).thenReturn(new ResumeOverview(b.id(), "B", 0, null, "b0"));
    var memory = mock(ChatMemory.class);
    when(memory.get(anyString())).thenReturn(List.of());
    var shell =
        new ProjectShellActivity(
            projects,
            mock(ProjectAgentEventStore.class),
            mock(ProjectRunStateStore.class),
            new WorkingSet(),
            memory);
    shell.setResumeOverview(service);
    var text = new StringBuilder();
    shell.attach(
        new ShellEventOutput() {
          public void print(String s) {
            text.append(s);
          }

          public void println(String s) {
            text.append(s).append('\n');
          }

          public void flush() {}
        });
    assertTrue(text.toString().contains("A  2 件"));
    assertTrue(text.toString().contains("request-a"));
    text.setLength(0);
    shell.restore(a);
    assertFalse(text.toString().contains("request-a"));
    projects.cd(bPath.toString());
    shell.restore(b);
    text.setLength(0);
    projects.cd(directory.toString());
    shell.restore(a);
    assertTrue(text.toString().contains("変更なし"));
    when(service.overview(a.id())).thenThrow(new IllegalStateException("DB unavailable"));
    assertDoesNotThrow(() -> shell.restore(a));
  }

  @Test
  void projectCommandRestoresBeforeAnySessionBackgroundPresentation() throws Exception {
    var projects =
        new ProjectService(directory, new ProjectRegistry(directory.resolve("projects.json")));
    var activity = mock(ProjectShellActivity.class);
    var client = projects.newClient();
    var cd =
        new picocli.CommandLine(
            new dev.mikoto2000.rei.core.command.ProjectCommand.CdCommand(projects));
    ShellProjectCommands.configure(cd, projects, client, activity);
    assertEquals(0, cd.execute(directory.toString()));
    verify(activity).restore(any());
    verify(activity, never()).refreshSession();
  }
}
