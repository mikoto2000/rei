package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatRunAdmission;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatRunConnectionQueueTest {
  @TempDir Path root;

  @ParameterizedTest @EnumSource(AgentRunContext.Mode.class)
  void queueCapturesConnectionBeforeAnyDispatchInEveryMode(AgentRunContext.Mode mode) {
    var connections = connections();
    var scheduled = new ArrayList<Runnable>();
    var used = new ArrayList<String>();
    var router = new ConversationInputRouter(scheduled::add, (owner, prompt, queue) ->
        used.add(connections.require(owner).profileId()));
    router.setChatRunAdmission(admission(connections));
    var first = owner("first-run", mode);
    router.submit(first, "request");
    assertThat(connections.require(first).profileId()).isEqualTo("first");
    connections.select("second");
    var second = owner("second-run", mode);
    router.submit(second, "request");
    assertThat(connections.require(second).profileId()).isEqualTo("second");
    while (!scheduled.isEmpty()) scheduled.removeFirst().run();
    assertThat(used).containsExactly("first", "second");
    assertThatThrownBy(() -> connections.require(first)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> connections.require(second)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectedDispatchDoesNotLeaveAnAcceptedConnection() {
    var connections = connections();
    var router = new ConversationInputRouter(task -> { throw new RejectedExecutionException(); },
        (owner, prompt, queue) -> {});
    router.setChatRunAdmission(admission(connections));
    var owner = owner("rejected", AgentRunContext.Mode.EXCLUSIVE);
    assertThatThrownBy(() -> router.submit(owner, "request"))
        .isInstanceOf(RejectedExecutionException.class);
    assertThatThrownBy(() -> connections.require(owner)).isInstanceOf(IllegalStateException.class);
  }

  private ChatRunAdmission admission(ChatRunConnections connections) {
    return new ChatRunAdmission() {
      public void accept(AgentRunContext owner, String prompt) { connections.accept(owner); }
      public void release(AgentRunContext owner) { connections.release(owner); }
    };
  }

  @Test
  void cancelledQueuedRunReleasesItsConnection() {
    var connections = connections();
    var scheduled = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(scheduled::add, (owner, prompt, queue) -> {});
    router.setChatRunAdmission(admission(connections));
    router.submit(owner("running", AgentRunContext.Mode.EXCLUSIVE), "request");
    var queued = owner("queued", AgentRunContext.Mode.EXCLUSIVE);
    router.submit(queued, "request");
    assertThat(connections.require(queued)).isNotNull();
    assertThat(router.cancelQueued(queued.runId())).isTrue();
    assertThatThrownBy(() -> connections.require(queued)).isInstanceOf(IllegalStateException.class);
    scheduled.removeFirst().run();
  }

  @Test
  void failedAcceptanceNeverSchedulesWork() {
    var scheduled = new ArrayList<Runnable>();
    var released = new java.util.concurrent.atomic.AtomicInteger();
    var router = new ConversationInputRouter(scheduled::add, (owner, prompt, queue) -> {});
    router.setChatRunAdmission(new ChatRunAdmission() {
      public void accept(AgentRunContext owner, String prompt) {
        throw new IllegalStateException("connection unavailable");
      }
      public void release(AgentRunContext owner) { released.incrementAndGet(); }
    });
    assertThatThrownBy(() -> router.submit(owner("rejected", AgentRunContext.Mode.EXCLUSIVE), "request"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("connection unavailable");
    assertThat(scheduled).isEmpty();
    assertThat(released).hasValue(1);
  }

  @Test
  void independentOperationsDoNotAcceptAChatConnection() {
    var scheduled = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(scheduled::add, (owner, prompt, queue) -> {});
    router.setChatRunAdmission(new ChatRunAdmission() {
      public void accept(AgentRunContext owner, String prompt) {
        throw new AssertionError("Independent operation used normal-chat selection");
      }
      public void release(AgentRunContext owner) {}
    });
    var called = new java.util.concurrent.atomic.AtomicBoolean();
    router.submitOperation(owner("operation", AgentRunContext.Mode.EXCLUSIVE),
        () -> called.set(true), Runnable::run);
    scheduled.removeFirst().run();
    assertThat(called).isTrue();
  }

  private AgentRunContext owner(String run, AgentRunContext.Mode mode) {
    return new AgentRunContext(run, "session", root, "project",
        AgentRunContext.RequestSource.SHELL, mode);
  }

  private ChatRunConnections connections() {
    var values = new HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "first");
    for (var name : java.util.List.of("first", "second")) {
      var prefix = "rei.llm.features.chat.endpoints." + name + ".";
      values.put(prefix + "base-url", "http://" + name + ".invalid");
      values.put(prefix + "authentication", "none");
      values.put(prefix + "model", name + "-model");
    }
    var props = new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.llm", LlmProperties.class).get();
    var catalog = new ChatEndpointCatalog(props);
    return new ChatRunConnections(catalog, new RunLlmConnectionFactory(catalog, props,
        ignored -> { throw new AssertionError("No legacy fallback"); }, ignored -> null));
  }
}
