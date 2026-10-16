package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.goal.GoalCompletionGate;
import dev.mikoto2000.rei.goal.GoalCompletionProgress;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.goal.GoalWaitRepository;
import dev.mikoto2000.rei.goal.GoalWaitService;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/goals")
public class GoalController {
  private final GoalRepository repository;
  private final GoalLoopService loop;

  public GoalController(GoalRepository repository, GoalLoopService loop) {
    this.repository = repository;
    this.loop = loop;
  }

  private dev.mikoto2000.rei.core.project.ProjectRegistry projects;
  private dev.mikoto2000.rei.application.session.SessionLifecycle sessions;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureCreation(
      dev.mikoto2000.rei.core.project.ProjectRegistry projects,
      dev.mikoto2000.rei.application.session.SessionLifecycle sessions) {
    this.projects = projects;
    this.sessions = sessions;
  }

  public record CreateRequest(
      String sessionId,
      String objective,
      String file,
      String sha256,
      String criteriaJson,
      String completionJson,
      Integer maxRuns,
      Integer maxLlmCalls)
      implements StrictApiRequest {}

  @PostMapping
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public GoalRepository.Goal create(
      @PathVariable String projectId, @RequestBody CreateRequest request) {
    if (request == null
        || request.sessionId() == null
        || request.sessionId().isBlank()
        || request.sessionId().length() > 256
        || request.objective() == null
        || request.objective().isBlank()
        || request.objective().length() > 4096
        || request.criteriaJson() != null && (request.file() != null || request.sha256() != null)
        || request.completionJson() != null && request.completionJson().length() > 32768)
      throw new IllegalArgumentException("Invalid Goal creation request");
    int runs = request.maxRuns() == null ? 3 : request.maxRuns(),
        calls = request.maxLlmCalls() == null ? 20 : request.maxLlmCalls();
    if (runs < 1 || runs > 10 || calls < 1 || calls > 100)
      throw new IllegalArgumentException("Goal budgets: 1..10 Runs and 1..100 LLM calls");
    if (projects == null || sessions == null)
      throw new IllegalStateException("Goal creation unavailable");
    var project =
        projects
            .resolveById(projectId)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    sessions.validate(request.sessionId(), project.id());
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext(
            java.util.UUID.randomUUID().toString(),
            request.sessionId(),
            project.root(),
            project.id(),
            dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB);
    if (request.criteriaJson() == null && request.completionJson() == null)
      return control(
          () ->
              loop.create(
                  owner, request.objective(), request.file(), request.sha256(), runs, calls));
    var criteria =
        request.criteriaJson() == null
            ? List.of(new GoalRepository.FileCriterion(request.file(), request.sha256()))
            : GoalRepository.parseCriteria(request.criteriaJson());
    if (request.completionJson() == null)
      return control(() -> loop.create(owner, request.objective(), criteria, runs, calls));
    var definition = GoalRepository.parseCompletion(request.completionJson());
    return control(
        () -> loop.create(owner, request.objective(), criteria, definition, runs, calls));
  }

  @GetMapping
  public List<GoalRepository.Goal> list(@PathVariable String projectId) {
    return repository.list(projectId);
  }

  @GetMapping("/{id}")
  public GoalRepository.Goal show(@PathVariable String projectId, @PathVariable String id) {
    return repository.get(projectId, id);
  }

  @GetMapping("/{id}/completion-progress")
  public GoalCompletionProgress progress(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.progress(projectId, id));
  }

  public record History(
      List<GoalRepository.History> history, List<GoalRepository.Attempt> attempts) {}

  @GetMapping("/{id}/history")
  public History history(@PathVariable String projectId, @PathVariable String id) {
    return new History(repository.history(projectId, id), repository.attempts(projectId, id));
  }

  @PostMapping("/{id}/verify")
  public GoalLoopService.Inspection verify(
      @PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.verify(projectId, id));
  }

  @PutMapping("/{id}/completion")
  public GoalRepository.Goal defineCompletionJson(
      @PathVariable String projectId, @PathVariable String id, @RequestBody String json) {
    return defineCompletion(projectId, id, GoalRepository.parseCompletion(json));
  }

  public GoalRepository.Goal defineCompletion(
      String projectId, String id, GoalCompletionGate.Definition definition) {
    return control(() -> loop.defineCompletion(projectId, id, definition));
  }

  @PostMapping("/{id}/completion-evidence")
  public GoalRepository.Goal attachCompletionJson(
      @PathVariable String projectId, @PathVariable String id, @RequestBody String json) {
    return attachCompletion(projectId, id, GoalRepository.parseCompletionProof(json));
  }

  public GoalRepository.Goal attachCompletion(
      String projectId, String id, GoalCompletionGate.Proof proof) {
    return control(() -> loop.attachCompletion(projectId, id, proof));
  }

  @PostMapping("/{id}/run")
  public GoalRepository.Goal run(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.run(projectId, id));
  }

  @PostMapping("/{id}/cancel")
  public GoalRepository.Goal cancel(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> loop.cancel(projectId, id));
  }

  public record ReconcileRequest(String expectedRunId, boolean acknowledgeUncertainSideEffects) {}

  @PostMapping("/{id}/reconcile")
  public GoalRepository.Goal reconcile(
      @PathVariable String projectId,
      @PathVariable String id,
      @RequestBody ReconcileRequest request) {
    if (request == null
        || !request.acknowledgeUncertainSideEffects()
        || request.expectedRunId() == null
        || request.expectedRunId().isBlank())
      throw new IllegalArgumentException(
          "Inspect effects and acknowledge the exact saved Run before reconciliation");
    return control(
        () ->
            loop.reconcile(
                projectId,
                id,
                request.expectedRunId().equals("none") ? null : request.expectedRunId()));
  }

  private GoalWaitService waits;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void configureWaits(GoalWaitService waits) {
    this.waits = waits;
  }

  private GoalWaitService waits() {
    if (waits == null) throw new IllegalStateException("Goal waiting unavailable");
    return waits;
  }

  public record WaitRequest(String dependencyId, String reason, String sessionId)
      implements StrictApiRequest {
    public WaitRequest(String dependencyId, String reason) {
      this(dependencyId, reason, null);
    }
  }

  public record WaitVersion(long expectedVersion) {
    public WaitVersion {
      if (expectedVersion < 1) throw new IllegalArgumentException("Positive wait version required");
    }
  }

  @PostMapping("/{id}/wait")
  public GoalWaitRepository.Wait waitFor(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitRequest request) {
    if (request == null) throw new IllegalArgumentException("Dependency and reason required");
    if (request.sessionId() != null
        && !request.sessionId().equals(repository.get(projectId, id).sessionId()))
      throw new IllegalArgumentException("Select the owning Goal Session");
    return control(() -> waits().waitFor(projectId, id, request.dependencyId(), request.reason()));
  }

  @GetMapping("/{id}/wait")
  public GoalWaitRepository.Wait waiting(@PathVariable String projectId, @PathVariable String id) {
    return control(() -> waits().show(projectId, id));
  }

  @PostMapping("/{id}/wait/activate")
  public GoalWaitRepository.Wait activateWait(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitVersion request) {
    return control(() -> waits().activate(projectId, id, version(request)));
  }

  @PostMapping("/{id}/wait/resume")
  public GoalWaitRepository.Wait resumeWait(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitVersion request) {
    return control(() -> waits().resume(projectId, id, version(request)));
  }

  @PostMapping("/{id}/wait/cancel")
  public GoalWaitRepository.Wait cancelWait(
      @PathVariable String projectId, @PathVariable String id, @RequestBody WaitVersion request) {
    return control(() -> waits().cancel(projectId, id, version(request)));
  }

  private long version(WaitVersion request) {
    if (request == null) throw new IllegalArgumentException("Wait revision required");
    return request.expectedVersion();
  }

  private static <T> T control(Supplier<T> action) {
    try {
      return action.get();
    } catch (IllegalStateException conflict) {
      throw new OperationConflictException();
    }
  }
}
