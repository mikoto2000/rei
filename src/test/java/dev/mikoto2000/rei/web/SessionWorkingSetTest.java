package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.working.*;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

class SessionWorkingSetTest {
  @TempDir Path directory;
  @Test void sessionsHaveIndependentWorkingSetsAndContinuationRestoresItsOwnFiles() {
    String previous = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", directory.toString());
    String project = UUID.randomUUID().toString();
    try (var application = new AnnotationConfigApplicationContext()) {
      application.registerBean(Clock.class, Clock::systemUTC);
      application.registerBean(AgentEventFactory.class, () -> new AgentEventFactory(Clock.systemUTC()));
      application.registerBean(AgentEventBus.class, InMemoryAgentEventBus::new);
      application.register(ProjectScopeConfiguration.class, WorkingSetConfiguration.class);
      application.refresh();
      var working = application.getBean(WorkingSet.class);
      var first = new AgentRunContext("one", "project:" + project + ":chat:first", directory, project);
      var second = new AgentRunContext("two", "project:" + project + ":chat:second", directory, project);
      try (var scope = AgentRunScope.open(first)) { working.recordRead(directory.resolve("first.txt")); }
      try (var scope = AgentRunScope.open(second)) {
        assertThat(working.getFiles()).isEmpty();
        working.recordRead(directory.resolve("second.txt"));
      }
      try (var scope = AgentRunScope.open(first)) {
        assertThat(working.getFiles()).hasSize(1);
        assertThat(working.getFiles().getFirst().path()).endsWith("first.txt");
      }
    } finally {
      if (previous == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", previous);
    }
  }
}
