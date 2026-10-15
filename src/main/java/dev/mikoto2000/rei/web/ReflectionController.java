package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.reflection.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

/** Scoped human reflection review. No tool invocation or automatic memory promotion. */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/projects/{projectId}/reflections")
public final class ReflectionController {
  private final GoalReflectionRepository repository;
  private final GoalReflectionService service;
  private final ProjectRegistry projects;
  private final SessionQueryService sessions;
  private RunReflectionRepository runs;
  private RunReflectionService runService;
  private VerifiedReflectionMemoryService promotion;
  private ReflectionLessonService lessons;

  public record Text(String text) {}

  public record Collection(String id) implements StrictApiRequest {}

  public record Scope(String sessionId) implements StrictApiRequest {}

  public record Review(
      String sessionId,
      String id,
      String sourceKind,
      ReflectionLessonService.Kind kind,
      Long revision,
      String evidenceHash,
      String note,
      List<String> evidence,
      String observation)
      implements StrictApiRequest {}

  public ReflectionController(
      GoalReflectionRepository repository,
      GoalReflectionService service,
      ProjectRegistry projects,
      SessionQueryService sessions) {
    this.repository = repository;
    this.service = service;
    this.projects = projects;
    this.sessions = sessions;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRuns(RunReflectionRepository runs) {
    this.runs = runs;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRunService(RunReflectionService runService) {
    this.runService = runService;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPromotion(VerifiedReflectionMemoryService promotion) {
    this.promotion = promotion;
  }

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setLessons(ReflectionLessonService lessons) {
    this.lessons = lessons;
  }

  @GetMapping
  public Text list(@PathVariable String projectId) {
    project(projectId);
    return text(repository.list(projectId));
  }

  @GetMapping("/{id}")
  public Text show(@PathVariable String projectId, @PathVariable String id) {
    project(projectId);
    return text(repository.get(projectId, id));
  }

  @PostMapping("/collect")
  public Text collect(@PathVariable String projectId, @RequestBody Collection request) {
    project(projectId);
    if (request == null) throw new IllegalArgumentException("Goal identity required");
    return text(service.collect(projectId, identity(request.id())));
  }

  @GetMapping("/runs")
  public Text runs(@PathVariable String projectId) {
    project(projectId);
    return text(required(runs).list(projectId));
  }

  @GetMapping("/runs/{id}")
  public Text run(@PathVariable String projectId, @PathVariable String id) {
    project(projectId);
    return text(required(runs).get(projectId, id));
  }

  @PostMapping("/runs/{id}/collect")
  public Text collectRun(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Scope request) {
    if (request == null) throw new IllegalArgumentException("Session required");
    var owner = owner(projectId, request.sessionId());
    return text(required(runService).collect(projectId, owner.conversationId(), identity(id)));
  }

  @PostMapping("/{id}/promote")
  public Text promote(@PathVariable String projectId, @PathVariable String id) {
    project(projectId);
    return text(required(promotion).promote(projectId, identity(id)));
  }

  @GetMapping("/lessons")
  public Text lessons(@PathVariable String projectId, @RequestParam String sessionId) {
    return text(required(lessons).list(owner(projectId, sessionId)));
  }

  @GetMapping("/lessons/{id}")
  public Text lesson(
      @PathVariable String projectId, @PathVariable String id, @RequestParam String sessionId) {
    return text(required(lessons).get(owner(projectId, sessionId), identity(id)));
  }

  @PostMapping("/lessons/observe")
  public Text observe(@PathVariable String projectId, @RequestBody Review request) {
    return text(
        required(lessons)
            .observe(
                reviewOwner(projectId, request),
                request.sourceKind() == null ? "RUN" : request.sourceKind(),
                identity(request.id())));
  }

  @PostMapping("/lessons/observe-correction")
  public Text observeCorrection(@PathVariable String projectId, @RequestBody Review request) {
    return text(
        required(lessons)
            .observeCorrection(
                reviewOwner(projectId, request),
                identity(request.id()),
                request.observation(),
                request.note()));
  }

  @PostMapping("/lessons/proposals")
  public Text propose(@PathVariable String projectId, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(
        required(lessons)
            .propose(
                owner,
                request.kind() == null
                    ? ReflectionLessonService.Kind.FAILURE_PATTERN
                    : request.kind(),
                request.note(),
                request.evidence()));
  }

  @PostMapping("/lessons/{id}/validate")
  public Text validate(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(
        required(lessons)
            .validate(
                owner, identity(id), revision(request), request.evidenceHash(), request.note()));
  }

  @PostMapping("/lessons/{id}/counterexamples")
  public Text counterexample(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(
        required(lessons)
            .counterexample(
                owner, identity(id), revision(request), request.observation(), request.note()));
  }

  @PostMapping("/lessons/{id}/corrections")
  public Text correct(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(required(lessons).correct(owner, identity(id), revision(request), request.note()));
  }

  @PostMapping("/lessons/{id}/forget")
  public Text forget(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Review request) {
    var owner = reviewOwner(projectId, request);
    return text(required(lessons).forget(owner, identity(id), revision(request)));
  }

  private ProjectContext project(String id) {
    return projects.resolveById(id).orElseThrow(() -> new ResourceNotFoundException("Project"));
  }

  private AgentRunContext owner(String projectId, String sessionId) {
    var project = project(projectId);
    identity(sessionId);
    if (!sessions.getSession(sessionId).projectId().equals(projectId))
      throw new IllegalArgumentException("Session belongs to another Project");
    return new AgentRunContext(
        "human-reflection",
        sessionId,
        project.root(),
        projectId,
        AgentRunContext.RequestSource.WEB);
  }

  private AgentRunContext reviewOwner(String projectId, Review request) {
    if (request == null) throw new IllegalArgumentException("Review required");
    return owner(projectId, request.sessionId());
  }

  private static long revision(Review request) {
    return request.revision() == null ? -1 : request.revision();
  }

  private static String identity(String id) {
    if (id == null
        || id.isBlank()
        || id.length() > 128
        || id.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Identity required");
    return id;
  }

  private static <T> T required(T service) {
    if (service == null) throw new IllegalStateException("Reflection operation unavailable");
    return service;
  }

  private static Text text(Object result) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));
  }
}
