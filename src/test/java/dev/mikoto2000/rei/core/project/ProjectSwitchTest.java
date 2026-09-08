package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectSwitchTest {
  @TempDir Path temp;
  @Test void switchRestoresIdentityAndCapturedRunRemainsOwnedByA() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var service = new ProjectService(a, new ProjectRegistry(temp.resolve("data/projects.json")));
    var first = service.currentContext();
    var run = new dev.mikoto2000.rei.core.chat.AgentRunContext("run", first, "chat:main");
    service.cd(b.toString());
    assertThat(service.currentContext().id()).isNotEqualTo(first.id());
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(run)) {
      assertThat(ProjectService.contextForOperation()).isEqualTo(first);
    }
    service.cd(a.toString());
    assertThat(service.currentContext()).isEqualTo(first);
    assertThat(run.conversationId()).isEqualTo(first.conversationId("chat:main"));
    assertThat(Files.exists(a.resolve(".rei"))).isFalse();
  }
}
