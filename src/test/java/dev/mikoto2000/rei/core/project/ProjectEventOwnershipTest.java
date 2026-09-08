package dev.mikoto2000.rei.core.project;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;

class ProjectEventOwnershipTest {
  @Test void factoryAndBusPreserveCapturedProjectOnEventsWithoutExplicitRunId() {
    var project = new ProjectContext(UUID.randomUUID().toString(), "a", Path.of("a"));
    var run = new AgentRunContext("run", project, "chat:main");
    var bus = new InMemoryAgentEventBus();
    List<AgentEvent> observed = new ArrayList<>(); bus.subscribe(observed::add);
    try (var scope = AgentRunScope.open(run)) {
      bus.publish(new AgentEventFactory(Clock.systemUTC()).messageStarted("message", "assistant"));
    }
    assertThat(observed.getFirst().projectId()).isEqualTo(project.id());
    assertThat(observed.getFirst().runId()).isEqualTo("run");
  }
}
