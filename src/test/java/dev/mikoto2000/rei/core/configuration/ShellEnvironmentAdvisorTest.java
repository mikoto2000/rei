package dev.mikoto2000.rei.core.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.shellautomation.ShellEnvironmentAdvisor;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class ShellEnvironmentAdvisorTest {
  @Test
  void springWiringSharesTheLauncherEnvironmentCache() {
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext(
            SystemShellService.class, ShellEnvironmentAdvisor.class)) {
      var shells = context.getBean(SystemShellService.class);
      assertSame(
          shells.automation().environment(),
          context.getBean(dev.mikoto2000.rei.shellautomation.ShellEnvironmentService.class));
      assertTrue(
          render(
                  context.getBean(ShellEnvironmentAdvisor.class),
                  new AgentRunContext("wiring", "session", Path.of("project")))
              .contains("# Shell execution environment"));
    }
  }

  @Test
  void usesCapturedOwnerAcrossConcurrentProjectsAndPreservesOriginalMessage() throws Exception {
    var advisor = new ShellEnvironmentAdvisor(new SystemShellService().automation().environment());
    var one = new AgentRunContext("run-a", "session-a", Path.of("a"), "project-a");
    var two = new AgentRunContext("run-b", "session-b", Path.of("b"), "project-b");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> render(advisor, one));
      var second = executor.submit(() -> render(advisor, two));
      assertTrue(first.get().contains(escaped(one.projectRoot())));
      assertFalse(first.get().contains(escaped(two.projectRoot())));
      assertTrue(second.get().contains(escaped(two.projectRoot())));
      assertTrue(first.get().contains("$LASTEXITCODE"));
      assertTrue(first.get().contains("getShellEnvironment"));
    }
  }

  private String escaped(Path path) {
    return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\");
  }

  private String render(ShellEnvironmentAdvisor advisor, AgentRunContext run) {
    var request =
        ChatClientRequest.builder()
            .prompt(new Prompt(new UserMessage("build")))
            .context(Map.of(AgentRunContext.class.getName(), run))
            .build();
    var actual = advisor.before(request, null);
    assertEquals("build", request.prompt().getUserMessage().getText());
    return actual.prompt().getUserMessage().getText();
  }

  @Test
  void doesNotInventAProjectForRequestsWithoutOwner() {
    var advisor = new ShellEnvironmentAdvisor(new SystemShellService().automation().environment());
    var request = ChatClientRequest.builder().prompt(new Prompt("hello")).build();
    assertSame(request, advisor.before(request, null));
  }
}
