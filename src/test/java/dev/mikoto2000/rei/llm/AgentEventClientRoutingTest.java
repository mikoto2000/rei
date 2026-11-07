package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.ui.shell.ProjectShellActivity;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import dev.mikoto2000.rei.web.SseBridge;
import dev.mikoto2000.rei.web.WebApiEventDto;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** 非同期の通信エラーが呼び出し元のクライアントへ配送されることを検証する。 */
@Tag("integration")
class AgentEventClientRoutingTest {
  @Test
  void cliReceivesItsFailureOverSseWithoutLeakingItToShellOrAnotherCli(@TempDir Path root)
      throws Exception {
    var projects = new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")));
    try (var client = projects.newClient().open()) {
      var project = projects.currentContext();
      var caller = new AgentRunContext("cli-run", "cli-session", project.root(), project.id(),
          AgentRunContext.RequestSource.WEB);
      var other = new AgentRunContext("other-run", "other-session", project.root(), project.id(),
          AgentRunContext.RequestSource.WEB);
      var registry = new RunRegistry(Clock.systemUTC());
      registry.register(caller);
      registry.register(other);
      var bus = new InMemoryAgentEventBus();
      var factory = new AgentEventFactory(Clock.systemUTC());
      var shellOutput = mock(ShellEventOutput.class);
      var activity = new ProjectShellActivity(projects,
          new dev.mikoto2000.rei.event.ProjectAgentEventStore(root),
          new ProjectRunStateStore(root), new WorkingSet(), mock(ChatMemory.class));
      activity.attach(shellOutput);
      clearInvocations(shellOutput);
      bus.subscribe(activity);
      var callerSink = new Sink();
      var otherSink = new Sink();
      try (var bridge = new SseBridge(bus, new RunService(registry), "")) {
        bridge.connect(caller.runId(), callerSink);
        bridge.connect(other.runId(), otherSink);
        var prompt = new Prompt("request");
        var delegate = mock(ChatModel.class);
        var failure = new java.net.ConnectException("PRIVATE_HOST secret");
        when(delegate.stream(prompt)).thenReturn(Flux.error(failure));
        var model = new AgentEventChatModel(LlmFeature.CHAT, delegate, factory, bus);
        Flux<ChatResponse> stream;
        try (var scope = AgentRunScope.open(caller)) { stream = model.stream(prompt); }
        stream.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .onErrorComplete().blockLast(Duration.ofSeconds(5));
        bus.publish(factory.runFailed(caller.runId(),
            dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.safeError(failure))
            .withOwnership(caller));
        assertThat(callerSink.ended.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(callerSink.events).extracting(WebApiEventDto::type).containsExactly(
            "llm.request.started", "llm.request.failed", "agent.run.failed");
        assertThat(callerSink.events).allSatisfy(event -> {
          assertThat(event.runId()).isEqualTo(caller.runId());
          assertThat(event.sessionId()).isEqualTo(caller.conversationId());
        });
        assertThat(callerSink.events.get(1).payload().toString())
            .contains("connection_failed", "接続先へ接続できませんでした")
            .doesNotContain("PRIVATE_HOST", "secret");
        assertThat(otherSink.events).isEmpty();
        verifyNoInteractions(shellOutput);

        // Shell-originated failures still render on the Shell.
        var shell = new AgentRunContext("shell-run", project, "chat:main");
        try (var scope = AgentRunScope.open(shell)) {
          model.stream(prompt).onErrorComplete().blockLast(Duration.ofSeconds(5));
        }
        verify(shellOutput).println(contains("接続先へ接続できませんでした"));
        assertThat(otherSink.events).isEmpty();
      }
    }
  }

  private static class Sink implements SseBridge.Sink {
    final List<WebApiEventDto> events = new CopyOnWriteArrayList<>();
    final CountDownLatch ended = new CountDownLatch(1);

    public void event(WebApiEventDto event) { events.add(event); }
    public void heartbeat() {}
    public void complete(Throwable error) { ended.countDown(); }
  }
}
