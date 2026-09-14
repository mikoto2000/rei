package dev.mikoto2000.rei.ui.shell;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.command.ProjectCommand;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectCdRestorationTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  @TempDir Path temp;
  @Test void shellWorkerRestoresWithinItsClientAndFailedCdDoesNotRestore() throws Exception {
    Path b = Files.createDirectory(temp.resolve("b"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var client = projects.newClient();
    var activity = mock(ProjectShellActivity.class);
    doAnswer(invocation -> {
      assertThat(ProjectService.contextForOperation().root()).isEqualTo(b);
      return null;
    }).when(activity).restore(any());
    var shell = new picocli.CommandLine(new ProjectCommand.CdCommand(projects));
    shell.setErr(new java.io.PrintWriter(java.io.Writer.nullWriter()));
    ShellProjectCommands.configure(shell, projects, client, activity);
    try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      assertThat(worker.submit(() -> shell.execute(b.toString())).get()).isZero();
      assertThat(worker.submit(() -> shell.execute("missing")).get()).isNotZero();
      assertThat(worker.submit(ProjectService::contextForOperation).get()).isNull();
    }
    verify(activity, times(1)).restore(any());
    try (var scope = client.open()) { assertThat(projects.currentProject()).isEqualTo(b); }
  }

  @Test void cdRestoresTheNewProjectAfterSwitchingItsContext() throws Exception {
    Path b = Files.createDirectory(temp.resolve("b"));
    var projects = connect(new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json"))));
    var activity = mock(ProjectShellActivity.class);
    var command = new ProjectCommand.CdCommand(projects);
    var shell = new picocli.CommandLine(command);
    ShellProjectCommands.configure(shell, projects, projects.newClient(), activity);
    assertThat(shell.execute(b.toString())).isZero();
    verify(activity).restore(org.mockito.ArgumentMatchers.argThat(context -> context.root().equals(b)));
    assertThat(projects.currentProject()).isEqualTo(temp);
  }
}
