package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunWaitingException;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示親委譲の結果と状態の契約を検証するテスト。 */
class DotParentDelegationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うサービス。 */
  DotDelegationService service;

  /** 検証に使うリポジトリ。 */
  DotRequestRepository repo;

  /** 検証に使うプロジェクト。 */
  ProjectContext project;

  /** 検証に使う実行一覧。 */
  RunRegistry runs;

  /** 検証に使う依存条件一覧。 */
  PersistentDependencyRepository dependencies;

  /** 検証に使う再開用の記録一覧。 */
  PersistentCheckpointRepository checkpoints;

  /** 検証に使う所有者。 */
  AgentRunContext owner;

  /** 検証に使う実行。 */
  RunExecutionContext execution;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Clock clock = Clock.systemUTC();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, clock);
    project =
        new ProjectContext(
            UUID.randomUUID().toString(), "p", Files.createDirectory(root.resolve("project")));
    var projects = mock(ProjectService.class);
    when(projects.registeredProjects()).thenReturn(List.of(project));
    var sessions = mock(SessionRepository.class);
    when(sessions.findById("session"))
        .thenReturn(
            Optional.of(
                new SessionMetadata(
                    "session", project.id(), "session", clock.instant(), clock.instant())));
    var properties =
        new DotProperties(true, true, false, Set.of(project.id()), null, 0, 0, null, null, 0, 0);
    service = new DotDelegationService(repo, properties, projects, sessions);
    var data = new org.sqlite.SQLiteDataSource();
    data.setUrl("jdbc:sqlite:" + root.resolve("dependencies.db"));
    dependencies = new PersistentDependencyRepository(data, clock);
    runs = new RunRegistry(clock);
    owner =
        new AgentRunContext(UUID.randomUUID().toString(), "session", project.root(), project.id());
    runs.register(owner);
    runs.transition(owner.runId(), RunStatus.RUNNING, null);
    checkpoints = mock(PersistentCheckpointRepository.class);
    when(checkpoints.findByRun(project.id(), owner.runId()))
        .thenReturn(
            Optional.of(
                PersistentCheckpoint.initial(
                    UUID.randomUUID().toString(),
                    project.id(),
                    "session",
                    owner.runId(),
                    project.root(),
                    "task")));
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
                null,
                null));
    service.configureRuntime(runs, dependencies, checkpoints, policy);
    execution =
        new RunExecutionContext(
            owner.runId(),
            new OutputLimitRunBudget(2, 5),
            new ProgressEvaluator(project.root(), null),
            new AgentEventFactory(clock),
            e -> {});
    execution.setRunContext(owner);
    execution.setUserRequest("てんてんに技術調査を依頼してください");
    service.authoriseHumanInput(owner, execution.userRequest());
    execution.setToolPermissionGuard(
        new ToolPermissionGuard(policy, new AgentEventFactory(clock), e -> {}));
  }

  /** {@code input} の条件を再現するための補助情報を返す。 */
  Map<String, Object> input() {
    return Map.of("instruction", "Research design", "category", "TECH_RESEARCH");
  }

  /** 利用者の状態表示の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void authorizedParentCreatesDependencyAndSuspendsAtSavedToolBoundary() {
    var accepted = service.requestParent(execution, input());
    String id = (String) accepted.get("request_id");
    assertEquals(owner.runId(), repo.owner(project.id(), id).run());
    assertNotNull(repo.owner(project.id(), id).dependency());
    assertEquals(1, execution.budgetSnapshot().llmCalls());
    assertEquals(RunStatus.RUNNING, runs.get(owner.runId()).status());
    var history =
        List.<org.springframework.ai.chat.messages.Message>of(
            org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(
                    List.of(
                        new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(
                            "call", "requestDotResearch", "accepted")))
                .build());
    assertThrows(RunWaitingException.class, () -> execution.suspendIfWaiting(history));
    assertEquals(RunStatus.WAITING, runs.get(owner.runId()).status());
    assertThrows(RuntimeException.class, () -> service.requestParent(execution, input()));
    assertEquals(1, repo.list(Set.of(project.id()), "PENDING", null, 20).size());
  }

  /** 利用者の状態表示の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void userIntentNotGeneratedInstructionGrantsAuthority() {
    execution.setUserRequest("自分で調査してください");
    assertThrows(
        RuntimeException.class,
        () ->
            service.requestParent(
                execution, Map.of("instruction", "てんてんに依頼", "category", "TECH_RESEARCH")));
    execution.setUserRequest("てんてんに技術調査を依頼しないでください");
    assertThrows(RuntimeException.class, () -> service.requestParent(execution, input()));
    execution.setUserRequest("てんてんにレビューを依頼してください");
    assertThrows(RuntimeException.class, () -> service.requestParent(execution, input()));
    assertEquals(0, execution.budgetSnapshot().llmCalls());
  }

  /** 利用者の状態表示で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void mcpReadOnlyAndSubagentCannotDelegateAgain() {
    execution.setRunContext(
        new AgentRunContext(
            owner.runId(),
            "session",
            project.root(),
            project.id(),
            AgentRunContext.RequestSource.MCP,
            AgentRunContext.Mode.READ_ONLY));
    assertThrows(RuntimeException.class, () -> service.requestParent(execution, input()));
    execution.setRunContext(
        new AgentRunContext(owner.runId(), "subagent:child", project.root(), project.id()));
    assertThrows(RuntimeException.class, () -> service.requestParent(execution, input()));
    assertEquals(List.of(), repo.list(Set.of(project.id()), "PENDING", null, 20));
  }

  /** 利用者の状態表示で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void sharedExternalDelegationAndModelBudgetAreEnforced() {
    assertTrue(execution.claimExternalDelegation());
    assertThrows(RuntimeException.class, () -> service.requestParent(execution, input()));
    var limited =
        new RunExecutionContext(
            owner.runId(),
            new OutputLimitRunBudget(1, 0),
            new ProgressEvaluator(project.root(), null),
            new AgentEventFactory(clock),
            e -> {});
    limited.setRunContext(owner);
    limited.setUserRequest("てんてんに技術調査を依頼してください");
    limited.setToolPermissionGuard(mock(ToolPermissionGuard.class));
    assertThrows(RuntimeException.class, () -> service.requestParent(limited, input()));
    assertEquals(List.of(), repo.list(Set.of(project.id()), "PENDING", null, 20));
  }

  /** 利用者の状態表示で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void actualModelToolLoopMakesOneCallThenYieldsWithoutWaitingForDot() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    org.springframework.ai.chat.model.ChatModel model =
        new org.springframework.ai.chat.model.ChatModel() {
          /** 検証用に、利用者の状態表示で参照する選択可能な設定を返す。 */
          public org.springframework.ai.model.tool.ToolCallingChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }

          /** 検証用に、利用者の状態表示の要求を実行し、呼び出し元へ結果を返す。 */
          public org.springframework.ai.chat.model.ChatResponse call(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
          public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            calls.incrementAndGet();
            var tool =
                new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                    "call",
                    "function",
                    "requestDotResearch",
                    "{\"category\":\"TECH_RESEARCH\",\"instruction\":\"Research design\"}");
            return reactor.core.publisher.Flux.just(
                org.springframework.ai.chat.model.ChatResponse.builder()
                    .generations(
                        List.of(
                            new org.springframework.ai.chat.model.Generation(
                                org.springframework.ai.chat.messages.AssistantMessage.builder()
                                    .content("")
                                    .toolCalls(List.of(tool))
                                    .build())))
                    .build());
          }
        };
    var options =
        org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .toolCallbacks(new DotTools(service).callbacks())
            .toolContext(Map.of(RunExecutionContext.KEY, execution))
            .build();
    var controlled = new dev.mikoto2000.rei.core.stagnation.StagnationChatModel(model);
    assertThrows(
        RunWaitingException.class,
        () ->
            controlled.stream(
                    new org.springframework.ai.chat.prompt.Prompt(
                        List.of(
                            new org.springframework.ai.chat.messages.UserMessage(
                                execution.userRequest())),
                        options))
                .blockLast());
    assertEquals(1, calls.get());
    assertEquals(RunStatus.WAITING, runs.get(owner.runId()).status());
  }

  /** 利用者の状態表示の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void generatedScheduledTextWithoutHumanAdmissionCannotGrantPermission() {
    var synthetic =
        new AgentRunContext(UUID.randomUUID().toString(), "session", project.root(), project.id());
    runs.register(synthetic);
    runs.transition(synthetic.runId(), RunStatus.RUNNING, null);
    execution.setRunContext(synthetic);
    assertThrows(DotException.class, () -> service.requestParent(execution, input()));
  }
}
