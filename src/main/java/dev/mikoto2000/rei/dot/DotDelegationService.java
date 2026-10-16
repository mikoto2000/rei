package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DotDelegationService {
  final DotRequestRepository repo;
  final DotProperties properties;
  final ProjectService projects;
  final SessionRepository sessions;

  public DotDelegationService(
      DotRequestRepository repo,
      DotProperties properties,
      ProjectService projects,
      SessionRepository sessions) {
    this.repo = repo;
    this.properties = properties;
    this.projects = projects;
    this.sessions = sessions;
  }

  ProjectContext project(String id) {
    if (!properties.enabled()) throw new DotException("FEATURE_DISABLED");
    if (!properties.allowedProjects().contains(id)) throw new DotException("NOT_FOUND");
    return projects.registeredProjects().stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new DotException("NOT_FOUND"));
  }

  private dev.mikoto2000.rei.application.run.RunRegistry runtimeRuns;
  private dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies;
  private dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpointRepository;
  private dev.mikoto2000.rei.core.policy.ToolPermissionPolicy policy;
  private java.time.Clock clock = java.time.Clock.systemUTC();

  private record Permit(
      dev.mikoto2000.rei.core.chat.AgentRunContext owner,
      Set<String> categories,
      Instant expires,
      String inputHash) {}

  private final java.util.concurrent.ConcurrentMap<String, Permit> permits =
      new java.util.concurrent.ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  void runtime(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.application.run.RunRegistry>
          runs,
      dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies,
      dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints,
      dev.mikoto2000.rei.core.policy.ToolPermissionPolicy policy,
      java.time.Clock clock) {
    this.clock = clock;
    configureRuntime(runs.getIfAvailable(), dependencies, checkpoints, policy);
  }

  public void configureRuntime(
      dev.mikoto2000.rei.application.run.RunRegistry runs,
      dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies,
      dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints,
      dev.mikoto2000.rei.core.policy.ToolPermissionPolicy policy) {
    this.runtimeRuns = runs;
    this.dependencies = dependencies;
    this.checkpointRepository = checkpoints;
    this.policy = policy;
  }

  /**
   * Called only by human Web/Shell admission, never from scheduler/model/MCP callbacks. Restart
   * drops permits.
   */
  public void authoriseHumanInput(dev.mikoto2000.rei.core.chat.AgentRunContext owner, String text) {
    permits.entrySet().removeIf(e -> !e.getValue().expires().isAfter(clock.instant()));
    if (!properties.enabled()
        || !properties.parentLlmEnabled()
        || owner == null
        || owner.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE
        || owner.voiceInput()
        || owner.requestSource() == dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.MCP
        || owner.conversationId().startsWith("subagent:")
        || !properties.allowedProjects().contains(owner.projectId())) return;
    var categories = new HashSet<>(DotAuthorization.categories(text));
    categories.retainAll(properties.allowedCategories());
    if (!categories.isEmpty() && permits.size() < 4096)
      permits.put(
          owner.runId(),
          new Permit(
              owner,
              Set.copyOf(categories),
              clock.instant().plus(properties.defaultTimeout()),
              dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(text)));
  }

  public Map<String, Object> requestParent(
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution, Map<String, Object> input) {
    if (!properties.parentLlmEnabled()
        || execution == null
        || runtimeRuns == null
        || dependencies == null
        || checkpointRepository == null
        || policy == null
        || !policy.enforced()) throw new DotException("PARENT_DELEGATION_DISABLED");
    execution.checkActive();
    var owner = execution.runContext();
    if (owner == null
        || owner.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.EXCLUSIVE
        || owner.requestSource() == dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.MCP
        || owner.conversationId().startsWith("subagent:")
        || owner.voiceInput()) throw new DotException("PARENT_NOT_ALLOWED");
    if (execution.hasParentReservation())
      throw new DotException("MANAGED_GOAL_REQUIRES_EXISTING_CONTINUATION");
    var project = project(owner.projectId());
    if (!project.root().equals(owner.projectRoot())) throw new DotException("OWNER_CHANGED");
    sessions
        .findById(owner.conversationId())
        .filter(s -> s.projectId().equals(owner.projectId()))
        .orElseThrow(() -> new DotException("SESSION_NOT_FOUND"));
    var current = runtimeRuns.get(owner.runId());
    if (!current.context().equals(owner)
        || current.status() != dev.mikoto2000.rei.application.run.RunStatus.RUNNING
        || !runtimeRuns.ownsExecution(owner.runId())) throw new DotException("RUN_NOT_ACTIVE");
    var snapshot = DotPayload.publication(input);
    String category = (String) snapshot.get("category");
    var permit = permits.get(owner.runId());
    if (permit == null
        || !permit.owner().equals(owner)
        || !permit.expires().isAfter(clock.instant())
        || !permit.categories().contains(category)
        || !DotAuthorization.categories(execution.userRequest()).contains(category)
        || !properties.allowedCategories().contains(category))
      throw new DotException("RUN_PERMISSION_REQUIRED");
    var checkpoint =
        checkpointRepository
            .findByRun(owner.projectId(), owner.runId())
            .filter(
                c ->
                    c.projectRoot().equals(owner.projectRoot().toString())
                        && c.sessionId().equals(owner.conversationId())
                        && c.status().equals("RUNNING"))
            .orElseThrow(() -> new DotException("CHECKPOINT_REQUIRED"));
    int timeout =
        DotPayload.integer(
            input.get("timeout"), (int) properties.defaultTimeout().toSeconds(), 1, 86400);
    var specification = new LinkedHashMap<String, Object>(snapshot);
    specification.put("timeout", timeout);
    try {
      execution.checkToolPermission(
          "requestDotResearch",
          dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(specification));
    } catch (java.io.IOException e) {
      throw new DotException("INVALID_ARGUMENT");
    }
    execution.checkModelTokenBudget();
    if (!execution.claimExternalDelegation()) throw new DotException("DELEGATION_ALREADY_USED");
    execution.consumeNextLlmCall();
    var accepted =
        repo.create(
            owner.projectId(),
            owner.conversationId(),
            owner.runId(),
            owner.projectRoot().toString(),
            snapshot,
            Duration.ofSeconds(timeout));
    String id = (String) accepted.get("request_id");
    try {
      var dependency =
          dependencies.create(
              owner,
              new dev.mikoto2000.rei.core.dependency.DependencySpec(
                  dev.mikoto2000.rei.core.dependency.DependencySpec.Kind.DOT_REQUEST, id, null),
              Duration.ofDays(properties.resultRetentionDays()),
              List.of());
      repo.bind(
          id,
          owner.projectId(),
          checkpoint.taskId(),
          dependency.id(),
          Map.of(
              "budget",
              execution.budgetSnapshot(),
              "authority",
              policy.authorityFingerprint(),
              "human_input_hash",
              permit.inputHash()));
      execution.setDotWait(history -> suspend(execution, history));
      return repo.get(owner.projectId(), id);
    } catch (RuntimeException failure) {
      repo.abandonAdmission(owner.projectId(), id);
      throw failure;
    }
  }

  private boolean suspend(
      dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution,
      List<org.springframework.ai.chat.messages.Message> history) {
    if (history.isEmpty()
        || !(history.getLast()
            instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool)
        || tool.getResponses().stream().noneMatch(r -> r.name().equals("requestDotResearch")))
      return false;
    var owner = execution.runContext();
    var request =
        repo.byRun(owner.projectId(), owner.runId())
            .orElseThrow(() -> new DotException("NOT_FOUND"));
    if (Set.of("CANCELLED", "FAILED", "EXPIRED", "REJECTED").contains(request.status()))
      return false;
    var current = runtimeRuns.get(owner.runId());
    if (!current.context().equals(owner)
        || current.status() != dev.mikoto2000.rei.application.run.RunStatus.RUNNING
        || !runtimeRuns.ownsExecution(owner.runId())) throw new DotException("OWNER_CHANGED");
    if (!runtimeRuns.transition(
        owner.runId(), dev.mikoto2000.rei.application.run.RunStatus.WAITING, null))
      throw new DotException("WAIT_FAILED");
    repo.publish(owner.projectId(), request.id());
    return true;
  }

  @jakarta.annotation.PostConstruct
  void recover() {
    repo.recoverAdmissions();
  }

  public Map<String, Object> createHuman(
      String project, String session, Map<String, Object> input) {
    var owner = project(project);
    if (session != null)
      sessions
          .findById(session)
          .filter(s -> s.projectId().equals(project))
          .orElseThrow(() -> new DotException("SESSION_NOT_FOUND"));
    var creation = new LinkedHashMap<String, Object>(input);
    String requestId =
        creation.containsKey("request_id") ? DotPayload.id(creation, "request_id") : null;
    creation.remove("request_id");
    var snapshot = DotPayload.publication(creation);
    if (!properties.allowedCategories().contains(snapshot.get("category")))
      throw new DotException("CATEGORY_NOT_ALLOWED");
    int timeout =
        DotPayload.integer(
            input.get("timeout"), (int) properties.defaultTimeout().toSeconds(), 1, 86400);
    return repo.create(
        project,
        session,
        null,
        owner.root().toString(),
        snapshot,
        Duration.ofSeconds(timeout),
        requestId);
  }

  public List<Map<String, Object>> list(String project) {
    project(project);
    return repo.open(project);
  }

  public Map<String, Object> get(String project, String id) {
    project(project);
    return repo.status(project, id);
  }

  public Map<String, Object> result(String project, String id) {
    project(project);
    return repo.result(project, id);
  }

  public Map<String, Object> cancel(String project, String id) {
    project(project);
    return repo.cancel(project, id);
  }
}
