package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import reactor.core.publisher.Flux;

class SubAgentRunnerTest {
  @TempDir Path directory;
  final List<AgentEvent> events = new CopyOnWriteArrayList<>();
  final CommandCancellationService cancellation = new CommandCancellationService();
  final AtomicInteger toolCalls = new AtomicInteger();
  final SubAgentToolPolicy policy = new SubAgentToolPolicy(Set.of("readMultiFile", "runCommand", "delegateTask"));
  ToolCallback callback() {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("readMultiFile").description("read").inputSchema("{}").build(); }
      public String call(String input) { toolCalls.incrementAndGet(); return "private intermediate result"; }
    };
  }
  SubAgentRunner runner(Function<Prompt, Flux<ChatResponse>> response, String timeout) throws Exception {
    return runner(response, timeout, () -> List.of(callback()));
  }
  SubAgentRunner runner(Function<Prompt, Flux<ChatResponse>> response, String timeout,
      java.util.function.Supplier<List<ToolCallback>> toolFactory) throws Exception {
    Files.writeString(directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer").replace("120s", timeout));
    var registry = new SubAgentRegistry(directory, new SubAgentDefinitionLoader(policy, model -> true));
    registry.reload();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt prompt) { throw new AssertionError("must use cancellable streaming"); }
      public Flux<ChatResponse> stream(Prompt prompt) { return response.apply(prompt); }
    };
    return new SubAgentRunner(registry, policy, ignored -> model,
        ignored -> ToolCallingChatOptions.builder().model("inherited-model").build(),
        toolFactory, cancellation, new AgentEventFactory(Clock.systemUTC()), events::add, Clock.systemUTC());
  }
  ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
  ChatResponse tool(String name) {
    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
        new AssistantMessage.ToolCall("call-1", "function", name, "{}"))).build())));
  }
  @Test void reviewerHasIndependentHistoryAndRestrictedToolsAndCorrelatedEvents() throws Exception {
    List<Prompt> requests = new CopyOnWriteArrayList<>();
    var runner = runner(p -> { requests.add(p); return Flux.just(requests.size() == 1 ? tool("readMultiFile") : answer("review")); }, "2s");
    var parent = new AgentRunContext("parent", "chat:main", directory);
    try (var scope = AgentRunScope.open(parent)) {
      var result = runner.run("reviewer", "review this", "explicit context");
      assertThat(result.status()).isEqualTo(SubAgentResult.Status.COMPLETED);
      assertThat(result.output()).isEqualTo("review");
      assertThat(AgentRunScope.current()).isEqualTo(parent);
      assertThat(result.startedAt()).isBeforeOrEqualTo(result.completedAt());
    }
    assertThat(requests.getFirst().getInstructions()).extracting(Message::getText)
        .containsExactly("Review independently.\n", "review this\n\nContext:\nexplicit context");
    assertThat(requests.get(1).getInstructions()).anyMatch(m -> m instanceof ToolResponseMessage);
    var options = (ToolCallingChatOptions) requests.getFirst().getOptions();
    assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name()).containsExactly("readMultiFile");
    assertThat(options.getInternalToolExecutionEnabled()).isFalse();
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.MESSAGE_DELTA || e.type() == AgentEventType.MESSAGE_COMPLETED);
    var lifecycle = events.stream().filter(e -> e.payload() instanceof SubAgentLifecyclePayload).toList();
    assertThat(lifecycle).extracting(AgentEvent::type).containsExactly(AgentEventType.SUBAGENT_STARTED, AgentEventType.SUBAGENT_COMPLETED);
    assertThat(lifecycle).allMatch(e -> ((SubAgentLifecyclePayload)e.payload()).parentRunId().equals("parent"));
    assertThat(lifecycle.getFirst().runId()).isEqualTo(lifecycle.getLast().runId()).isNotEqualTo("parent");
  }
  @Test void maxStepsBoundsLlmCalls() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var runner = runner(p -> { calls.incrementAndGet(); return Flux.just(tool("readMultiFile")); }, "2s");
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.MAX_STEPS_EXCEEDED);
    assertThat(calls.get()).isEqualTo(2);
  }
  @Test void timeoutDisposesActiveUpstream() throws Exception {
    CountDownLatch disposed = new CountDownLatch(1);
    var runner = runner(p -> Flux.<ChatResponse>never().doOnCancel(disposed::countDown), "30ms");
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.TIMEOUT);
    assertThat(disposed.await(1, TimeUnit.SECONDS)).isTrue();
  }
  @Test void parentCancellationStopsChildWithoutReplacingParentSubscription() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch disposed = new CountDownLatch(1);
    var runner = runner(p -> Flux.<ChatResponse>never().doOnSubscribe(s -> started.countDown()).doOnCancel(disposed::countDown), "5s");
    var parent = new AgentRunContext("parent", "chat:main", directory);
    AtomicReference<SubAgentResult> result = new AtomicReference<>();
    AtomicBoolean parentDisposed = new AtomicBoolean();
    try (var scope = AgentRunScope.open(parent)) {
      cancellation.begin(null);
      cancellation.register(() -> parentDisposed.set(true));
      Thread child = Thread.ofVirtual().start(() -> {
        try (var childScope = AgentRunScope.open(parent)) { result.set(runner.run("reviewer", "task", null)); }
      });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      cancellation.cancel();
      child.join(2000);
      assertThat(child.isAlive()).isFalse();
      assertThat(result.get().status()).isEqualTo(SubAgentResult.Status.CANCELLED);
      assertThat(disposed.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(parentDisposed).isTrue();
      cancellation.clear();
    }
  }
  @Test void unknownAgentFailureAndUnrequestedCallsFailClosed() throws Exception {
    var runner = runner(p -> Flux.just(tool("runCommand")), "2s");
    assertThat(runner.run("missing", "task", null).status()).isEqualTo(SubAgentResult.Status.UNKNOWN_AGENT);
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(toolCalls).hasValue(0);
    assertThat(events).anyMatch(e -> e.type() == AgentEventType.SUBAGENT_FAILED);
  }
  @Test void eventsDoNotCopySecretsOrUnboundedTask() throws Exception {
    var runner = runner(p -> Flux.error(new IllegalStateException("password=hidden")), "2s");
    runner.run("reviewer", "password=hidden " + "x".repeat(1000), null);
    assertThat(events.toString()).doesNotContain("hidden").doesNotContain("x".repeat(121));
  }
  @Test void timeoutInterruptsToolWorkerAndDoesNotStartNextIteration() throws Exception {
    CountDownLatch interrupted = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    ToolCallback blocking = new ToolCallback() {
      public ToolDefinition getToolDefinition() { return callback().getToolDefinition(); }
      public String call(String input) {
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException error) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        return "late result";
      }
    };
    var runner = runner(p -> { calls.incrementAndGet(); return Flux.just(tool("readMultiFile")); }, "500ms", () -> List.of(blocking));
    assertThat(runner.run("reviewer", "task", null).status()).isEqualTo(SubAgentResult.Status.TIMEOUT);
    assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(calls).hasValue(1);
  }
  @Test void siblingsHaveSeparateRunIdsAndIndividualCancellation() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    var runner = runner(p -> Flux.<ChatResponse>never().doOnSubscribe(s -> started.countDown()), "5s");
    var parent = new AgentRunContext("parent", "chat:main", directory);
    var results = new CopyOnWriteArrayList<SubAgentResult>();
    Runnable task = () -> { try (var scope = AgentRunScope.open(parent)) { results.add(runner.run("reviewer", "task", null)); } };
    var a = Thread.ofVirtual().start(task);
    var b = Thread.ofVirtual().start(task);
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    var ids = events.stream().filter(e -> e.type() == AgentEventType.SUBAGENT_STARTED).map(AgentEvent::runId).toList();
    assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    assertThat(runner.cancel(ids.getFirst())).isTrue();
    assertThat(runner.cancel(ids.getLast())).isTrue();
    a.join(2000); b.join(2000);
    assertThat(results).hasSize(2).allMatch(result -> result.status() == SubAgentResult.Status.CANCELLED);
    assertThat(runner.cancel(ids.getFirst())).isFalse();
  }
}
