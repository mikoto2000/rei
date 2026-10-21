package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationLogEntry;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

/** プロジェクト実行Switch外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectRunSwitchIntegrationTest
    extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 会話の実行で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void toolRunAndInterventionFinishInAAfterShellSwitchesToB() throws Exception {
    String prior = System.getProperty("rei.data-dir");
    Path data = temp.resolve("data");
    System.setProperty("rei.data-dir", data.toString());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Path a = Files.createDirectory(temp.resolve("a"));
      Path b = Files.createDirectory(temp.resolve("b"));
      var projects =
          connect(new ProjectService(a, new ProjectRegistry(data.resolve("projects.json"))));
      var run = new AgentRunContext("run-a", projects.currentContext(), "chat:main");
      var bus = new InMemoryAgentEventBus();
      var events = new AgentEventFactory(Clock.systemUTC());
      var store = new ProjectAgentEventStore(data);
      bus.subscribe(store);
      var calls = new AtomicInteger();
      ChatModel model =
          new ChatModel() {
            /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
            @Override
            public ToolCallingChatOptions getOptions() {
              return ToolCallingChatOptions.builder().build();
            }

            /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
            public ChatResponse call(Prompt p) {
              throw new UnsupportedOperationException();
            }

            /** {@code stream} の条件を再現するための補助情報を返す。 */
            public Flux<ChatResponse> stream(Prompt p) {
              if (calls.incrementAndGet() == 1)
                return Flux.just(
                    new ChatResponse(
                        List.of(
                            new Generation(
                                AssistantMessage.builder()
                                    .content("")
                                    .toolCalls(
                                        List.of(
                                            new AssistantMessage.ToolCall(
                                                "tool", "function", "readFile", "{}")))
                                    .build()))));
              assertThat(
                      p.getInstructions().stream()
                          .filter(UserMessage.class::isInstance)
                          .map(Message::getText))
                  .contains("keep README");
              return Flux.just(
                  new ChatResponse(List.of(new Generation(new AssistantMessage("done in A")))));
            }
          };
      var tool =
          new ToolCallback() {
            /** 検証用に、会話の実行で参照するツール定義を返す。 */
            public ToolDefinition getToolDefinition() {
              return ToolDefinition.builder()
                  .name("readFile")
                  .description("read")
                  .inputSchema("{}")
                  .build();
            }

            /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
            public String call(String input) {
              entered.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IllegalStateException("test tool release timed out");
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
              assertThat(AgentRunScope.current().projectRoot()).isEqualTo(a);
              return "read in A";
            }

            /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
            public String call(String input, ToolContext context) {
              return call(input);
            }
          };
      var memory = MessageWindowChatMemory.builder().maxMessages(100).build();
      var client =
          ChatClient.builder(new StagnationChatModel(model))
              .defaultAdvisors(new RunAwareToolCallingAdvisor())
              .defaultTools(new ToolEventCallbackDecorator(tool, events, bus))
              .defaultAdvisors(
                  RunScopedAdvisor.wrap(List.of(MessageChatMemoryAdvisor.builder(memory).build())))
              .build();
      var holder = mock(ModelHolderService.class);
      when(holder.get()).thenReturn("test");
      var service =
          new ChatExecutionService(
              client, holder, new CommandCancellationService(), Optional.empty(), events, bus);
      service.setChatMemory(memory);
      service.setConversationLogStore(new ConversationLogStore());
      var queue =
          new UserInterventionQueue(
              entry ->
                  bus.publish(
                      events
                          .intervention(run.runId(), entry.id(), entry.text(), false)
                          .withOwnership(run)));
      var future = executor.submit(() -> service.execute(run, "fix", queue));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        queue.offer("keep README");
        projects.cd(b.toString());
        release.countDown();
        assertThat(future.get(10, TimeUnit.SECONDS).success()).isTrue();
        assertThat(new ConversationLogStore().readAll()).isEmpty();
        assertThat(store.recent(projects.currentContext().id(), 100)).isEmpty();
        assertThat(store.recent(run.projectId(), 100))
            .allMatch(event -> run.projectId().equals(event.projectId()))
            .anyMatch(event -> event.type() == AgentEventType.USER_INTERVENTION_APPLIED)
            .anyMatch(event -> event.type() == AgentEventType.AGENT_RUN_COMPLETED);
        projects.cd(a.toString());
        assertThat(new ConversationLogStore().readAll())
            .extracting(ConversationLogEntry::content)
            .contains("keep README", "done in A");
        assertThat(memory.get(run.conversationId()))
            .anyMatch(
                message ->
                    message instanceof UserMessage && message.getText().equals("keep README"));
      } finally {
        release.countDown();
      }
    } finally {
      if (prior == null) System.clearProperty("rei.data-dir");
      else System.setProperty("rei.data-dir", prior);
    }
  }
}
