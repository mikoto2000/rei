package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import static org.assertj.core.api.Assertions.*;

class ProjectCancellationTest {
  @TempDir Path temp;
  @Test void cancellingBDoesNotInterruptA() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var projects = new ProjectService(a, new ProjectRegistry(temp.resolve("projects.json")));
    var run = new AgentRunContext("run", projects.currentContext(), "chat:main");
    var cancellation = new CommandCancellationService();
    try (var scope = AgentRunScope.open(run)) { cancellation.begin(Thread.currentThread()); }
    projects.cd(b.toString());
    assertThat(cancellation.cancelCurrentProject()).isFalse();
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    cancellation.clear();
  }
}
