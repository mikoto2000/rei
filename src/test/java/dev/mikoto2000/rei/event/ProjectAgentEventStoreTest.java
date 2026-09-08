package dev.mikoto2000.rei.event;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.ui.shell.*;
import static org.assertj.core.api.Assertions.*;

class ProjectAgentEventStoreTest {
  @TempDir Path temp;
  AgentEventFactory factory = new AgentEventFactory(Clock.systemUTC());
  AgentRunContext run(String name) { return new AgentRunContext(name,
      new ProjectContext(UUID.randomUUID().toString(), name, temp), "chat:main"); }

  @Test void projectsHaveIndependentDurableSequencesAndTypedPayloads() {
    var a = run("a"); var b = run("b");
    var store = new ProjectAgentEventStore(temp);
    store.append(factory.runStarted("a", "test", null).withOwnership(a));
    store.append(factory.intervention("a", "input", "keep README", false).withOwnership(a));
    store.append(factory.runStarted("b", "test", null).withOwnership(b));
    var reopened = new ProjectAgentEventStore(temp);
    reopened.append(factory.intervention("a", "input", "keep README", true).withOwnership(a));
    assertThat(reopened.recent(a.projectId(), 2)).extracting(AgentEvent::sequence).containsExactly(2L, 3L);
    assertThat(reopened.recent(b.projectId(), 10)).extracting(AgentEvent::sequence).containsExactly(1L);
    assertThat(reopened.recent(a.projectId(), 1).getFirst().payload()).isInstanceOf(UserInterventionPayload.class);
    assertThat(reopened.lastSequence(a.projectId())).isEqualTo(3);
  }

  @Test void persistedAndLiveEventsUseTheSameRenderer() {
    var a = run("a"); var store = new ProjectAgentEventStore(temp);
    var event = factory.intervention("a", "input", "keep README", true).withOwnership(a);
    store.append(event);
    assertThat(render(store.recent(a.projectId(), 1).getFirst())).isEqualTo(render(event));
  }
  @Test void interruptedTailDoesNotHideFutureEventsOrResetSequence() throws Exception {
    var a = run("a"); var store = new ProjectAgentEventStore(temp);
    store.append(factory.runStarted("a", "test", null).withOwnership(a));
    Path file = temp.resolve("projects/" + a.projectId() + "/events/events.jsonl");
    Files.writeString(file, "{\"incomplete\":", StandardOpenOption.APPEND);
    var reopened = new ProjectAgentEventStore(temp);
    reopened.append(factory.intervention("a", "input", "日本語の追加入力", true).withOwnership(a));
    assertThat(reopened.recent(a.projectId(), 10)).extracting(AgentEvent::sequence).containsExactly(1L, 2L);
    assertThat(reopened.readAfter(a.projectId(), 1, 10)).hasSize(1);
  }

  @Test void busContinuesWhenStoreCannotWrite() throws Exception {
    var blocked = Files.writeString(temp.resolve("blocked"), "file");
    var store = new ProjectAgentEventStore(blocked);
    var bus = new InMemoryAgentEventBus();
    bus.subscribe(store);
    List<AgentEvent> live = new ArrayList<>(); bus.subscribe(live::add);
    var a = run("a");
    bus.publish(factory.runStarted("a", "test", null).withOwnership(a));
    assertThat(live).hasSize(1);
  }
  private String render(AgentEvent event) {
    var text = new StringBuilder();
    var renderer = new ShellAgentEventRenderer(new ShellEventOutput() {
      public void print(String value) { text.append(value); }
      public void println(String value) { text.append(value).append('\n'); }
      public void flush() {}
    });
    renderer.onEvent(event); return text.toString();
  }
}
