package dev.mikoto2000.rei.ui.shell;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.command.ProjectCommand;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectCdRestorationTest {
  @TempDir Path temp;
  @Test void cdRestoresTheNewProjectAfterSwitchingItsContext() throws Exception {
    Path b = Files.createDirectory(temp.resolve("b"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var activity = mock(ProjectShellActivity.class);
    var command = new ProjectCommand.CdCommand(projects);
    command.setActivity(activity);
    assertThat(new picocli.CommandLine(command).execute(b.toString())).isZero();
    verify(activity).restore(projects.currentContext());
    assertThat(projects.currentProject()).isEqualTo(b);
  }
}
