package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;

class ProjectRunStateStoreTest {
  @TempDir Path temp;
  @Test void lastRunStateRestoresWithoutAnEventStore() {
    var project = new ProjectContext(UUID.randomUUID().toString(), "a", temp);
    var run = new AgentRunContext("run", project, "chat:main");
    var store = new ProjectRunStateStore(temp);
    var events = new AgentEventFactory(Clock.systemUTC());
    store.onEvent(events.runStarted("run", "test", null).withOwnership(run));
    assertThat(store.read(project.id()).orElseThrow().status()).isEqualTo("RUNNING");
    store.onEvent(events.runCompleted("run", 1, null, null, null, null).withOwnership(run));
    assertThat(new ProjectRunStateStore(temp).read(project.id()).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(store.read(UUID.randomUUID().toString())).isEmpty();
  }
}
