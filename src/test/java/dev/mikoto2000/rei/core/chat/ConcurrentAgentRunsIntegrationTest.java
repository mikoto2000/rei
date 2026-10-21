package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/** 並行エージェント実行一覧外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ConcurrentAgentRunsIntegrationTest
    extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 会話の実行で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void timeoutAndProviderFailureLeaveNoActiveRun() {
    for (var error :
        List.of(new TimeoutException("timeout"), new IllegalStateException("provider failed"))) {
      ChatModel model =
          new ChatModel() {
            /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
            public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
              return org.springframework.ai.openai.OpenAiChatOptions.builder()
                  .model("test")
                  .build();
            }

            /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
            public ChatResponse call(Prompt p) {
              throw new UnsupportedOperationException();
            }

            /** {@code stream} の条件を再現するための補助情報を返す。 */
            public Flux<ChatResponse> stream(Prompt p) {
              return Flux.error(error);
            }
          };
      var cancellation = new CommandCancellationService();
      var holder = mock(ModelHolderService.class);
      when(holder.get()).thenReturn("test");
      var execution =
          new ChatExecutionService(
              ChatClient.builder(model).build(),
              holder,
              cancellation,
              Optional.empty(),
              new AgentEventFactory(Clock.systemUTC()),
              event -> {});
      var tasks = new ArrayList<Runnable>();
      var router =
          new ConversationInputRouter(
              tasks::add, (c, p, q) -> assertThat(execution.execute(c, p, q).success()).isFalse());
      router.submit(temp, "project:" + UUID.randomUUID() + ":chat:main", "request");
      assertThat(router.activeRuns()).hasSize(1);
      tasks.getFirst().run();
      assertThat(router.activeRuns()).isEmpty();
    }
  }

  /** 会話の実行の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void realExecutionCancelsBWhileAContinuesThenRemovesBoth() throws Exception {
    var projects =
        connect(
            new ProjectService(
                Files.createDirectory(temp.resolve("a")),
                new ProjectRegistry(temp.resolve("registry.json"))));
    var a = projects.currentContext();
    projects.cd(Files.createDirectory(temp.resolve("b")).toString());
    var b = projects.currentContext();
    var streams = new ConcurrentHashMap<String, FluxSink<ChatResponse>>();
    var entered = new CountDownLatch(2);
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            return Flux.create(
                sink -> {
                  streams.put(p.getUserMessage().getText(), sink);
                  entered.countDown();
                });
          }
        };
    var cancellation = new CommandCancellationService();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var events = new AgentEventFactory(Clock.systemUTC());
    var bus = new InMemoryAgentEventBus();
    var observed = new CopyOnWriteArrayList<AgentEvent>();
    bus.subscribe(observed::add);
    var execution =
        new ChatExecutionService(
            ChatClient.builder(model).build(), holder, cancellation, Optional.empty(), events, bus);
    try (var executor = new AgentRunConfiguration().agentRunExecutor()) {
      var router =
          new ConversationInputRouter(
              executor, (context, prompt, queue) -> execution.execute(context, prompt, queue));
      var onlyA = new CountDownLatch(1);
      var empty = new CountDownLatch(1);
      router.submit(a.root(), a.conversationId("chat:main"), "A");
      router.submit(b.root(), b.conversationId("chat:main"), "B");
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        router.onChange(
            () -> {
              var runs = router.activeRuns();
              if (runs.size() == 1 && runs.getFirst().projectId().equals(a.id())) onlyA.countDown();
              if (runs.isEmpty()) empty.countDown();
            });
        assertThat(router.activeRuns()).hasSize(2);
        assertThat(cancellation.cancelCurrentProject()).isTrue();
        assertThat(onlyA.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(streams.get("B").isCancelled()).isTrue();
        assertThat(streams.get("A").isCancelled()).isFalse();
        streams
            .get("A")
            .next(new ChatResponse(List.of(new Generation(new AssistantMessage("done A")))));
        streams.get("A").complete();
        assertThat(empty.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(observed)
            .anyMatch(
                e -> e.type() == AgentEventType.AGENT_RUN_COMPLETED && a.id().equals(e.projectId()))
            .anyMatch(
                e -> e.type() == AgentEventType.AGENT_RUN_FAILED && b.id().equals(e.projectId()));
      } finally {
        streams.values().forEach(FluxSink::complete);
      }
    }
  }
}
