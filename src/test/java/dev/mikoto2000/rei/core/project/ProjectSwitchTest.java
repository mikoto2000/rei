package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectSwitchTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  @TempDir Path temp;
  @Test void failedSessionLookupPreservesSelectionAndOtherClients() throws Exception {
    var sessions = org.mockito.Mockito.mock(dev.mikoto2000.rei.application.session.SessionRepository.class);
    var service = connect(new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")), sessions));
    var target = Files.createDirectory(temp.resolve("target"));
    var project = service.currentContext();
    service.selectSession("original");
    org.mockito.Mockito.when(sessions.findPage(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(1)))
        .thenThrow(new IllegalStateException("Cannot read sessions"));
    assertThatThrownBy(() -> service.cd(target.toString())).isInstanceOf(IllegalStateException.class);
    assertThat(service.currentContext()).isEqualTo(project);
    assertThat(service.currentSessionId()).isEqualTo("original");
    try (var scope = service.newClient().open()) {
      assertThat(service.currentSessionId()).isNull();
    }
    assertThat(service.currentSessionId()).isEqualTo("original");
  }
  @Test void switchRestoresIdentityAndCapturedRunRemainsOwnedByA() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var service = connect(new ProjectService(a, new ProjectRegistry(temp.resolve("data/projects.json"))));
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
