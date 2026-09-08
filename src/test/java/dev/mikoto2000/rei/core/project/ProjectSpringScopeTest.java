package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import dev.mikoto2000.rei.core.working.*;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;

class ProjectSpringScopeTest {
  @TempDir Path temp;
  @Test void injectedWorkingSetProxySwitchesTargets() throws Exception {
    String prior = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.resolve("data").toString());
    try (var beans = new AnnotationConfigApplicationContext()) {
      var a = Files.createDirectory(temp.resolve("a"));
      var b = Files.createDirectory(temp.resolve("b"));
      var projects = new ProjectService(a, new ProjectRegistry(temp.resolve("data/projects.json")));
      beans.registerBean(Clock.class, Clock::systemUTC);
      beans.registerBean(AgentEventFactory.class, () -> new AgentEventFactory(Clock.systemUTC()));
      beans.registerBean(AgentEventBus.class, InMemoryAgentEventBus::new);
      beans.register(ProjectScopeConfiguration.class, WorkingSetConfiguration.class);
      beans.refresh();
      var workingSet = beans.getBean(WorkingSet.class);
      workingSet.recordRead(a.resolve("A.java"));
      projects.cd(b.toString());
      assertThat(workingSet.getFiles()).isEmpty();
      projects.cd(a.toString());
      assertThat(workingSet.getFiles()).hasSize(1);
    } finally {
      if (prior == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", prior);
    }
  }
}
