package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Web会話実行の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class WebSessionExecutionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 受付済み識別子一覧プロジェクトを維持することを確認する。 */
  @Test
  void realRunnerKeepsSubmittedIdsProjectAndSessionMemoryIndependentOfCliSelection()
      throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var first = projects.resolve(Files.createDirectory(directory.resolve("first")));
    var second = projects.resolve(Files.createDirectory(directory.resolve("second")));
    var projectService = new ProjectService(first.root(), projects);
    var memory = MessageWindowChatMemory.builder().build();
    List<Prompt> prompts = new ArrayList<>();
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
          }
        };
    var client =
        ChatClient.builder(model)
            .defaultAdvisors(
                RunScopedAdvisor.wrap(
                    List.of(
                        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(
                                memory)
                            .build())))
            .build();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var cancellation = new CommandCancellationService();
    var bus = new InMemoryAgentEventBus();
    var factory = new AgentEventFactory(Clock.systemUTC());
    var turns = ConversationTurnStore.inMemory();
    var execution =
        new ChatExecutionService(client, holder, cancellation, Optional.empty(), factory, bus);
    execution.setChatMemory(memory);
    execution.setConversationTurnStore(turns);
    List<Runnable> tasks = new ArrayList<>();
    List<AgentRunContext> executed = new ArrayList<>();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    var router =
        new ConversationInputRouter(
            tasks::add,
            (context, prompt, queue) -> {
              executed.add(context);
              execution.execute(context, prompt, queue);
            });
    var registry = new RunRegistry(Clock.systemUTC());
    try (var runs = new RunService(registry, bus, factory, cancellation, router::cancelQueued);
        var scope = projectService.newClient().open()) {
      var submit =
          new ChatSubmitService(
              projects,
              new SessionRegistry(Clock.systemUTC()),
              registry,
              new dev.mikoto2000.rei.conversation.FileSessionRepository(
                  directory.resolve("sessions.json")),
              Clock.systemUTC(),
              (context, prompt) ->
                  router.submit(context, prompt, work -> runs.execute(context, work)));
      var a = submit.submit("alpha-marker", first.id(), null);
      var b = submit.submit("beta-marker", first.id(), null);
      var continuation = submit.submit("continue-alpha", first.id(), a.conversationId());
      projectService.cd(second.root().toString());
      while (!tasks.isEmpty()) tasks.removeFirst().run();
      assertThat(projectService.currentProject()).isEqualTo(second.root());
      assertThat(executed).containsExactly(a, b, continuation);
      assertThat(turns.read(a.conversationId()))
          .allSatisfy(
              turn -> {
                assertThat(turn.assistantMessage()).isEqualTo("answer");
                assertThat(turn.createdAt()).isNotNull();
              });
      assertThat(executed).allMatch(context -> context.projectRoot().equals(first.root()));
      assertThat(turns.read(a.conversationId()))
          .extracting(ConversationTurnStore.Turn::runId)
          .containsExactly(a.runId(), continuation.runId());
      assertThat(turns.read(b.conversationId()))
          .extracting(ConversationTurnStore.Turn::runId)
          .containsExactly(b.runId());
      assertThat(memory.get(b.conversationId()).toString())
          .contains("beta-marker")
          .doesNotContain("alpha-marker");
      assertThat(memory.get(a.conversationId()).toString())
          .contains("alpha-marker")
          .doesNotContain("beta-marker");
      assertThat(prompts.getLast().toString())
          .contains("alpha-marker")
          .doesNotContain("beta-marker");
      for (var context : executed) {
        assertThat(runs.get(context.runId()).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(events.stream().filter(event -> context.runId().equals(event.runId())).toList())
            .isNotEmpty()
            .allMatch(
                event ->
                    context.conversationId().equals(event.sessionId())
                        && context.runId().equals(event.turnId())
                        && first.id().equals(event.projectId()));
      }
    }
  }
}
