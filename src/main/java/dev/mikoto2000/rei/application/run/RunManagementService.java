package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.checkpoint.RunCheckpointMenu;
import dev.mikoto2000.rei.checkpoint.RunCheckpointMenu.Checkpoint;
import dev.mikoto2000.rei.checkpoint.RunCheckpointMenu.Run;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.ActiveRun;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.execution.ExecutionType;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.BackgroundExecutionPayload;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class RunManagementService implements RunCheckpointMenu.Access {
  @Override
  public List<Checkpoint> checkpoints(Run run) {
    return run.projectId() == null ? List.of() : checkpoints(run.projectId(), run.runId());
  }

  @Override
  public void discard(Run run, Checkpoint checkpoint) {
    discard(run.projectId(), run.runId(), checkpoint.taskId(), checkpoint.revision());
  }

  private final ProjectRegistry projects;
  private final ConversationInputRouter router;
  private final ProjectAgentEventStore events;
  private final ConversationTurnStore turns;
  private final PersistentCheckpointService checkpoints;
  private final org.springframework.beans.factory.ObjectProvider<RunRegistry> registry;

  public RunManagementService(
      ProjectRegistry projects,
      ConversationInputRouter router,
      ProjectAgentEventStore events,
      ConversationTurnStore turns,
      PersistentCheckpointService checkpoints,
      org.springframework.beans.factory.ObjectProvider<RunRegistry> registry) {
    this.projects = projects;
    this.router = router;
    this.events = events;
    this.turns = turns;
    this.checkpoints = checkpoints;
    this.registry = registry;
  }

  public List<Run> runs() {
    var projectsById = new HashMap<String, ProjectContext>();
    var result = new ArrayList<Run>();
    var active = router.activeExecutions();
    for (var project : projects.list()) {
      projectsById.put(project.id(), project);
      var starts = new HashMap<String, AgentEvent>();
      var terminals = new HashMap<String, AgentEvent>();
      for (var event : events.runLifecycle(project.id())) {
        if (event.runId() == null) continue;
        switch (event.type()) {
          case AGENT_RUN_STARTED, EXECUTION_STARTED -> starts.putIfAbsent(event.runId(), event);
          case AGENT_RUN_FAILED,
              AGENT_RUN_CANCELLED,
              AGENT_RUN_COMPLETED,
              EXECUTION_FAILED,
              EXECUTION_CANCELLED,
              EXECUTION_COMPLETED ->
              terminals.put(event.runId(), event);
          default -> {}
        }
      }
      for (var event : terminals.values()) {
        if (active.stream()
            .anyMatch(
                run -> project.id().equals(run.projectId()) && event.runId().equals(run.id())))
          continue;
        var start = starts.get(event.runId());
        if (event.payload() instanceof BackgroundExecutionPayload execution) {
          if (event.type() != AgentEventType.EXECUTION_COMPLETED)
            result.add(
                new Run(
                    project.id(),
                    summary(project.name()),
                    event.runId(),
                    summary(execution.request()),
                    execution.status(),
                    start == null ? null : start.timestamp().toString(),
                    summary(execution.output())));
          continue;
        }
        if (!(event.payload() instanceof AgentRunFailedPayload failed)) continue;
        var turn =
            event.sessionId() == null
                ? null
                : turns.findRun(event.sessionId(), event.runId()).orElse(null);
        var startedAt = start != null ? start.timestamp() : turn == null ? null : turn.createdAt();
        var error = failed.error();
        boolean cancelled =
            event.type() == AgentEventType.AGENT_RUN_CANCELLED
                || error != null && "cancelled".equals(error.code());
        String request =
            turn == null
                ? checkpoints.checkpointsForRun(project.id(), event.runId()).stream()
                    .findFirst()
                    .map(state -> state.request())
                    .orElse("")
                : turn.request();
        result.add(
            new Run(
                project.id(),
                summary(project.name()),
                event.runId(),
                summary(request),
                cancelled ? "CANCELLED" : "FAILED",
                startedAt == null ? null : startedAt.toString(),
                error == null || cancelled ? "" : summary(error.message())));
      }
    }
    for (var run : active) {
      var project = projectsById.get(run.projectId());
      String status =
          run.type() == ExecutionType.AGENT
              ? router.cancellationDiagnostic(run.id()).status()
              : run.status();
      result.add(
          new Run(
              run.projectId(),
              project == null ? "" : summary(project.name()),
              run.id(),
              summary(run.summary()),
              status.equals("NOT_FOUND") ? run.status() : status,
              run.startedAt().toString(),
              ""));
    }
    return result.stream()
        .sorted(
            Comparator.comparing(
                    (Run row) ->
                        row.startedAt() == null
                            ? java.time.Instant.MIN
                            : java.time.Instant.parse(row.startedAt()))
                .reversed()
                .thenComparing(Run::runId))
        .toList();
  }

  public List<Checkpoint> checkpoints(String project, String run) {
    requireProject(project);
    return checkpoints.checkpointsForRun(project, run).stream()
        .map(
            state ->
                new Checkpoint(
                    state.taskId(),
                    state.runId(),
                    state.originalRunId(),
                    state.createdAt().toString(),
                    state.status(),
                    state.revision(),
                    summary(state.request()),
                    !live(project, run) && checkpoints.canDiscard(state, id -> live(project, id))))
        .toList();
  }

  public void discard(String project, String run, String task, long revision) {
    requireProject(project);
    if (revision < 1) throw new IllegalArgumentException("Invalid checkpoint revision");
    if (checkpoints.checkpointsForRun(project, run).stream()
        .noneMatch(s -> s.taskId().equals(task))) throw new OperationConflictException();
    checkpoints.discardForRun(project, run, task, revision, id -> live(project, id));
  }

  private void requireProject(String project) {
    if (projects.resolveById(project).isEmpty()) throw new ResourceNotFoundException("Project");
  }

  private boolean live(String project, String id) {
    if (router.activeExecutions().stream()
        .anyMatch(run -> Objects.equals(project, run.projectId()) && id.equals(run.id())))
      return true;
    var current = registry.getIfAvailable();
    if (current == null) return false;
    try {
      var run = current.get(id);
      return project.equals(run.context().projectId()) && !run.status().isTerminal();
    } catch (RunNotFoundException absent) {
      return false;
    }
  }

  private static String summary(String value) {
    return value == null ? "" : ActiveRun.summary(CredentialRedactor.redact(value));
  }
}
