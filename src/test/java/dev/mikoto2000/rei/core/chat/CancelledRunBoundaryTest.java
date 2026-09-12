package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.skills.*;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CancelledRunBoundaryTest {
  @Test void cancellationDuringTopicRefreshCannotReportRunCompleted() {
    var topic = mock(dev.mikoto2000.rei.topic.TopicOrchestrator.class);
    doThrow(new CancellationException()).when(topic).onChatCompleted();
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var events = new CopyOnWriteArrayList<AgentEvent>();
    var client = ChatClient.builder(model(new AtomicInteger(), Flux.just(new ChatResponse(List.of(
        new Generation(new AssistantMessage("answer"))))))).build();
    var service = new ChatExecutionService(new FixedLlmChatClientProvider(client), holder,
        new FixedLlmModelProvider(), new LlmProperties(), new CommandCancellationService(), Optional.empty(), Optional.empty(),
        Optional.of(topic), Optional.empty(), Clock.systemUTC(), Optional.empty(), new AgentEventFactory(Clock.systemUTC()), events::add);
    assertThat(service.execute("work").status()).isEqualTo(ChatExecutionResult.Status.CANCELLED);
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.AGENT_RUN_COMPLETED);
  }
  @Test void cancelledSkillSelectionCannotPublishNormalResultOrStartChatEvenIfItReturnsLate() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var returned = new CountDownLatch(1);
    var advisorFinished = new CountDownLatch(1);
    var selection = mock(AgentSkillSelectionService.class);
    when(selection.select(anyString())).thenAnswer(invocation -> {
      entered.countDown();
      awaitIgnoringInterrupt(release);
      returned.countDown();
      return new AgentSkillSelection(List.of(), List.of(), List.of(), "work", 1L, List.of(), null);
    });
    var events = new CopyOnWriteArrayList<AgentEvent>();
    var advisor = new AgentSkillAdvisor(selection, new AgentSkillPromptRenderer(),
        new AgentEventFactory(Clock.systemUTC()), events::add) {
      @Override public String getName() { return "ObservedSkillAdvisor"; }
      @Override public org.springframework.ai.chat.client.ChatClientRequest before(
          org.springframework.ai.chat.client.ChatClientRequest request,
          org.springframework.ai.chat.client.advisor.api.AdvisorChain chain) {
        try { return super.before(request, chain); }
        finally { advisorFinished.countDown(); }
      }
    };
    var calls = new AtomicInteger();
    var client = ChatClient.builder(model(calls, Flux.empty())).defaultAdvisors(RunScopedAdvisor.wrap(List.of(advisor))).build();
    var cancellation = new CommandCancellationService();
    var service = service(client, cancellation, events);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var run = executor.submit(() -> service.execute("work"));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel();
        assertThat(run.get(5, TimeUnit.SECONDS).status()).isEqualTo(ChatExecutionResult.Status.CANCELLED);
      } finally { release.countDown(); }
      assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(advisorFinished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.SKILL_ROUTING_COMPLETED);
    assertThat(calls).hasValue(0);
  }

  @Test void inFlightCancellationDisposesRequestDiscardsGuidanceAndSkipsTopic() throws Exception {
    var subscribed = new CountDownLatch(1);
    var disposed = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var client = ChatClient.builder(model(calls, Flux.<ChatResponse>never()
        .doOnSubscribe(s -> subscribed.countDown()).doOnCancel(disposed::countDown))).build();
    var cancellation = new CommandCancellationService();
    var events = new CopyOnWriteArrayList<AgentEvent>();
    var service = service(client, cancellation, events);
    var queue = new UserInterventionQueue(); queue.offer("pending B");
    try (var executor = Executors.newSingleThreadExecutor()) {
      var run = executor.submit(() -> service.execute(new AgentRunContext("A", "chat:main", Path.of(".")), "A", queue));
      assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
      cancellation.cancel();
      assertThat(run.get(5, TimeUnit.SECONDS).status()).isEqualTo(ChatExecutionResult.Status.CANCELLED);
      assertThat(disposed.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(queue.drain()).isEmpty();
    assertThat(queue.offer("too late")).isFalse();
    assertThat(calls).hasValue(1);
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.AGENT_RUN_COMPLETED);
  }

  @Test void cancelledToolCannotStartNextToolOrNextLlmIteration() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var returned = new CountDownLatch(1);
    var secondToolCalls = new AtomicInteger();
    ToolCallback tool = new ToolCallback() {
      public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("work").description("work").inputSchema("{}").build(); }
      public String call(String input) {
        if (entered.getCount() == 0) { secondToolCalls.incrementAndGet(); return "unexpected"; }
        entered.countDown(); awaitIgnoringInterrupt(release); returned.countDown(); return "done";
      }
    };
    var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
        new AssistantMessage.ToolCall("a", "function", "work", "{}"),
        new AssistantMessage.ToolCall("b", "function", "work", "{}"))).build())));
    var calls = new AtomicInteger();
    var client = ChatClient.builder(new StagnationChatModel(model(calls, Flux.just(response)))).defaultToolCallbacks(tool).build();
    var cancellation = new CommandCancellationService();
    var service = service(client, cancellation, new CopyOnWriteArrayList<>());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var run = executor.submit(() -> service.execute("work"));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel();
        assertThat(run.get(5, TimeUnit.SECONDS).status()).isEqualTo(ChatExecutionResult.Status.CANCELLED);
      } finally { release.countDown(); }
      assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(calls).hasValue(1);
    assertThat(secondToolCalls).hasValue(0);
  }

  private static void awaitIgnoringInterrupt(CountDownLatch latch) {
    boolean done = false;
    while (!done) try { done = latch.await(5, TimeUnit.SECONDS); }
    catch (InterruptedException ignored) { /* Simulate an external API that does not honor interruption. */ }
  }
  private static ChatModel model(AtomicInteger calls, Flux<ChatResponse> result) {
    return new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) { calls.incrementAndGet(); return result; }
    };
  }
  private static ChatExecutionService service(ChatClient client, CommandCancellationService cancellation, List<AgentEvent> events) {
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var topic = mock(dev.mikoto2000.rei.topic.TopicOrchestrator.class);
    doAnswer(i -> { throw new AssertionError("Cancelled run must not refresh topics"); }).when(topic).onChatCompleted();
    return new ChatExecutionService(new FixedLlmChatClientProvider(client), holder,
        new FixedLlmModelProvider(), new LlmProperties(), cancellation, Optional.empty(), Optional.empty(),
        Optional.of(topic), Optional.empty(), Clock.systemUTC(), Optional.empty(),
        new AgentEventFactory(Clock.systemUTC()), events::add);
  }
}
