package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.policy.ApprovalContinuationService;
import dev.mikoto2000.rei.core.policy.ApprovalContinuationStore;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;

/** 承認チャット外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ApprovalChatIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void realChatReleasesItsWorkerThenContinuesInTheSameRunWithoutFailedNotificationOrNewArguments() {
    var clock = Clock.systemUTC();
    String project = UUID.randomUUID().toString();
    var owner = new AgentRunContext("run", "session", directory, project);
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("approval.db"));
    var approvals = new ToolApprovalRepository(source, clock);
    var store = new ApprovalContinuationStore(source);
    var registry = new RunRegistry(clock);
    registry.register(owner);
    registry.transition(owner.runId(), RunStatus.RUNNING, null);
    var bus = new InMemoryAgentEventBus();
    var events = new ArrayList<AgentEvent>();
    bus.subscribe(events::add);
    var router = new ConversationInputRouter(Runnable::run, (o, p, q) -> {});
    var cancellation = new CommandCancellationService();
    var runs =
        new RunService(
            registry, bus, new AgentEventFactory(clock), cancellation, router::cancelQueued);
    var projects = mock(ProjectRegistry.class);
    when(projects.resolveById(project))
        .thenReturn(Optional.of(new ProjectContext(project, "test", directory)));
    var sessions = mock(SessionRepository.class);
    when(sessions.findById("session"))
        .thenReturn(
            Optional.of(
                new SessionMetadata(
                    "session", project, "review", clock.instant(), clock.instant())));
    var beans = new StaticListableBeanFactory();
    beans.addBean("registry", registry);
    beans.addBean("runs", runs);
    beans.addBean("router", router);
    beans.addBean("projects", projects);
    beans.addBean("sessions", sessions);
    var continuation =
        new ApprovalContinuationService(
            approvals,
            store,
            beans.getBeanProvider(RunRegistry.class),
            beans.getBeanProvider(RunService.class),
            beans.getBeanProvider(ConversationInputRouter.class),
            beans.getBeanProvider(ChatExecutionService.class),
            beans.getBeanProvider(dev.mikoto2000.rei.slack.SlackAgentService.class),
            beans.getBeanProvider(ProjectRegistry.class),
            beans.getBeanProvider(SessionRepository.class),
            source,
            clock);
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(new ToolPermissionProperties(true, null, Set.of(), null)),
            new AgentEventFactory(clock),
            bus);
    guard.setApprovals(approvals);
    var effects = new AtomicInteger();
    var calls = new AtomicInteger();
    String arguments = "{\"request\":{\"command\":\"npm run textlint\",\"timeoutSeconds\":30}}";
    var tool =
        new ToolCallback() {
          /** 検証用に、会話の実行で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("runCommand")
                .description("validate")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            assertEquals(arguments, input);
            effects.incrementAndGet();
            return "validation passed";
          }
        };
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public org.springframework.ai.model.tool.ToolCallingChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt p) {
            throw new AssertionError();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public Flux<ChatResponse> stream(Prompt p) {
            if (calls.incrementAndGet() == 1)
              return Flux.just(
                  new ChatResponse(
                      List.of(
                          new Generation(
                              AssistantMessage.builder()
                                  .content("check")
                                  .toolCalls(
                                      List.of(
                                          new AssistantMessage.ToolCall(
                                              "command", "function", "runCommand", arguments)))
                                  .build()))));
            assertEquals(1, effects.get());
            assertTrue(
                p.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .anyMatch(
                        m ->
                            m.getResponses()
                                .getFirst()
                                .responseData()
                                .equals("validation passed")));
            return Flux.just(
                new ChatResponse(
                    List.of(new Generation(new AssistantMessage("review completed")))));
          }
        };
    var client =
        ChatClient.builder(new StagnationChatModel(model))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .defaultTools(List.of(tool))
            .build();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var settings = new LlmProperties();
    settings.getOutputLimit().setMaxLlmCallsPerRun(2);
    var chat =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            settings,
            cancellation,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            clock,
            Optional.empty(),
            new AgentEventFactory(clock),
            bus);
    beans.addBean("chat", chat);
    beans.addBean("continuation", continuation);
    chat.setContinuations(beans.getBeanProvider(RunContinuation.class));
    chat.setToolPermissionGuard(guard);
    var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
    chat.setCheckpoints(checkpoints);
    var first = chat.execute(owner, "review", new UserInterventionQueue());
    assertEquals(ChatExecutionResult.Status.WAITING, first.status());
    assertEquals(RunStatus.WAITING_APPROVAL, registry.get("run").status());
    assertEquals("tool_approval_waiting", first.stopCode());
    verify(checkpoints).finish(owner, "WAITING_APPROVAL");
    assertEquals(0, effects.get());
    assertEquals(1, calls.get());
    assertTrue(events.stream().noneMatch(e -> e.type() == AgentEventType.AGENT_RUN_FAILED));
    var request = approvals.list(project).getFirst();
    continuation.decide(project, request.id(), true, true);
    continuation.pump();
    continuation.pump();
    assertEquals(1, effects.get());
    assertEquals(2, calls.get());
    assertEquals(RunStatus.COMPLETED, registry.get("run").status());
    assertEquals(Set.of("run"), registry.runIds());
    assertEquals("COMPLETED", continuation.state(request.id()).orElseThrow());
    verify(checkpoints).finish(owner, "COMPLETED");
    verify(checkpoints, never()).finish(owner, "FAILED");
    assertTrue(events.stream().noneMatch(e -> e.type() == AgentEventType.AGENT_RUN_FAILED));
  }
}
