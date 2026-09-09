package dev.mikoto2000.rei.core.chat;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.event.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConcurrentAgentRunsIntegrationTest {
  @TempDir Path temp;
  @Test void timeoutAndProviderFailureLeaveNoActiveRun() {
    for (var error : List.of(new TimeoutException("timeout"), new IllegalStateException("provider failed"))) {
      ChatModel model = new ChatModel() {
        public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
        public Flux<ChatResponse> stream(Prompt p) { return Flux.error(error); }
      };
      var cancellation = new CommandCancellationService();
      var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
      var execution = new ChatExecutionService(ChatClient.builder(model).build(), holder, cancellation,
          Optional.empty(), new AgentEventFactory(Clock.systemUTC()), event -> {});
      var tasks = new ArrayList<Runnable>();
      var router = new ConversationInputRouter(tasks::add, (c,p,q) -> assertThat(execution.execute(c,p,q).success()).isFalse());
      router.submit(temp, "project:" + UUID.randomUUID() + ":chat:main", "request");
      assertThat(router.activeRuns()).hasSize(1);
      tasks.getFirst().run();
      assertThat(router.activeRuns()).isEmpty();
    }
  }
  @Test void realExecutionCancelsBWhileAContinuesThenRemovesBoth() throws Exception {
    var projects = new ProjectService(Files.createDirectory(temp.resolve("a")), new ProjectRegistry(temp.resolve("registry.json")));
    var a = projects.currentContext();
    projects.cd(Files.createDirectory(temp.resolve("b")).toString()); var b = projects.currentContext();
    var streams = new ConcurrentHashMap<String, FluxSink<ChatResponse>>();
    var entered = new CountDownLatch(2);
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) { return Flux.create(sink -> { streams.put(p.getUserMessage().getText(), sink); entered.countDown(); }); }
    };
    var cancellation = new CommandCancellationService();
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var events = new AgentEventFactory(Clock.systemUTC()); var bus = new InMemoryAgentEventBus();
    var observed = new CopyOnWriteArrayList<AgentEvent>(); bus.subscribe(observed::add);
    var execution = new ChatExecutionService(ChatClient.builder(model).build(), holder, cancellation, Optional.empty(), events, bus);
    try (var executor = new AgentRunConfiguration().agentRunExecutor()) {
      var router = new ConversationInputRouter(executor, (context,prompt,queue) -> execution.execute(context,prompt,queue));
      var onlyA = new CountDownLatch(1); var empty = new CountDownLatch(1);
      router.submit(a.root(), a.conversationId("chat:main"), "A");
      router.submit(b.root(), b.conversationId("chat:main"), "B");
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        router.onChange(() -> {
          var runs = router.activeRuns();
          if (runs.size() == 1 && runs.getFirst().projectId().equals(a.id())) onlyA.countDown();
          if (runs.isEmpty()) empty.countDown();
        });
        assertThat(router.activeRuns()).hasSize(2);
        assertThat(cancellation.cancelCurrentProject()).isTrue();
        assertThat(onlyA.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(streams.get("B").isCancelled()).isTrue();
        assertThat(streams.get("A").isCancelled()).isFalse();
        streams.get("A").next(new ChatResponse(List.of(new Generation(new AssistantMessage("done A")))));
        streams.get("A").complete();
        assertThat(empty.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(observed).anyMatch(e -> e.type() == AgentEventType.AGENT_RUN_COMPLETED && a.id().equals(e.projectId()))
            .anyMatch(e -> e.type() == AgentEventType.AGENT_RUN_FAILED && b.id().equals(e.projectId()));
      } finally { streams.values().forEach(FluxSink::complete); }
    }
  }
}
