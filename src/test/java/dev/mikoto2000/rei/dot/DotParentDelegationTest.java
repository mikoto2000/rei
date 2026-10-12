package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DotParentDelegationTest {
  @TempDir Path root;
  DotDelegationService service;
  DotRequestRepository repo;
  ProjectContext project;
  RunRegistry runs;
  PersistentDependencyRepository dependencies;
  PersistentCheckpointRepository checkpoints;
  AgentRunContext owner;
  RunExecutionContext execution;
  Clock clock = Clock.systemUTC();

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

  Map<String, Object> input() {
    return Map.of("instruction", "Research design", "category", "TECH_RESEARCH");
  }

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

  @Test
  void actualModelToolLoopMakesOneCallThenYieldsWithoutWaitingForDot() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    org.springframework.ai.chat.model.ChatModel model =
        new org.springframework.ai.chat.model.ChatModel() {
          public org.springframework.ai.model.tool.ToolCallingChatOptions getOptions() {
            return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
          }

          public org.springframework.ai.chat.model.ChatResponse call(
              org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException();
          }

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
