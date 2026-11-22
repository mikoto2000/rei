package dev.mikoto2000.rei.core.runmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.AgentRunStartedPayload;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.SqliteProjectAgentEventStore;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@Tag("integration")
class RunManagementServiceTest {
  @TempDir Path directory;

  @Test
  void candidateQueryFiltersLatestHeadsAndPreservesProjectOwnership() {
    var projects = mock(ProjectService.class);
    String one = "00000000-0000-0000-0000-000000000001";
    String two = "00000000-0000-0000-0000-000000000002";
    when(projects.registeredProjects())
        .thenReturn(
            java.util.List.of(
                new dev.mikoto2000.rei.core.project.ProjectContext(one, "One", directory),
                new dev.mikoto2000.rei.core.project.ProjectContext(
                    two, "Two", directory.resolve("two"))));
    var checkpoints = mock(PersistentCheckpointService.class);
    var failed = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.class);
    when(failed.taskId()).thenReturn("task");
    when(failed.status()).thenReturn("FAILED");
    when(failed.createdAt()).thenReturn(Instant.EPOCH);
    when(failed.request()).thenReturn("password=secret");
    var cancelled = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.class);
    when(cancelled.status()).thenReturn("CANCELLED");
    when(checkpoints.list(one)).thenReturn(java.util.List.of(cancelled));
    when(checkpoints.list(two)).thenReturn(java.util.List.of(failed));
    var service =
        new RunManagementService(
            projects,
            mock(ConversationInputRouter.class),
            mock(dev.mikoto2000.rei.event.ProjectAgentEventStore.class),
            mock(ConversationTurnStore.class),
            checkpoints,
            new StaticListableBeanFactory().getBeanProvider(RunRegistry.class));
    var result = service.candidates(null, "failed");
    assertEquals(1, result.size());
    assertEquals(two, result.getFirst().projectId());
    assertEquals("password=[REDACTED]", result.getFirst().request());
    assertTrue(service.candidates(one, "failed").isEmpty());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> service.candidates(null, "faild"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> service.history(null, "completed", 0));
    org.mockito.Mockito.verify(checkpoints, org.mockito.Mockito.never())
        .resumeForUser(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void completedHistoryFiltersBeforeLimitingAndSortsByCompletionAcrossProjects() {
    var projects = mock(ProjectService.class);
    var one =
        new dev.mikoto2000.rei.core.project.ProjectContext(
            "00000000-0000-0000-0000-000000000001", "One", directory);
    var two =
        new dev.mikoto2000.rei.core.project.ProjectContext(
            "00000000-0000-0000-0000-000000000002",
            "Two",
            directory.resolve("00000000-0000-0000-0000-000000000002"));
    when(projects.registeredProjects()).thenReturn(java.util.List.of(one, two));
    var events = mock(dev.mikoto2000.rei.event.ProjectAgentEventStore.class);
    when(events.runLifecycle("00000000-0000-0000-0000-000000000001"))
        .thenReturn(
            java.util.List.of(
                completed("a", "00000000-0000-0000-0000-000000000001", 10),
                completed("b", "00000000-0000-0000-0000-000000000001", 40)));
    when(events.runLifecycle("00000000-0000-0000-0000-000000000002"))
        .thenReturn(
            java.util.List.of(
                completed("c", "00000000-0000-0000-0000-000000000002", 20),
                completed("d", "00000000-0000-0000-0000-000000000002", 30)));
    var service =
        new RunManagementService(
            projects,
            mock(ConversationInputRouter.class),
            events,
            mock(ConversationTurnStore.class),
            mock(PersistentCheckpointService.class),
            new StaticListableBeanFactory().getBeanProvider(RunRegistry.class));
    assertEquals(
        java.util.List.of("b", "d", "c"),
        service.history(null, "completed", 3).stream()
            .map(dev.mikoto2000.rei.runmanagement.RunHistoryEntry::runId)
            .toList());
    assertEquals(
        java.util.List.of("b", "a"),
        service.history("00000000-0000-0000-0000-000000000001", "completed", 3).stream()
            .map(dev.mikoto2000.rei.runmanagement.RunHistoryEntry::runId)
            .toList());
  }

  private static AgentEvent completed(String run, String project, long seconds) {
    return new AgentEvent(
        run,
        0,
        Instant.EPOCH.plusSeconds(seconds),
        AgentEventType.AGENT_RUN_COMPLETED,
        1,
        "session",
        null,
        run,
        null,
        null,
        new dev.mikoto2000.rei.event.AgentRunCompletedPayload(run, 1),
        project);
  }

  @Test
  void persistedFailureWithoutCheckpointIsListedAfterRestart() throws Exception {
    try (var migration = new StorageMigrationCoordinator(directory)) {
      migration.prepare();
    }
    var projects = new ProjectService(directory, directory.resolve("projects.json"));
    var project = projects.currentContext();
    var eventStore = new SqliteProjectAgentEventStore(directory);
    var start = Instant.parse("2026-11-16T00:00:00Z");
    eventStore.append(
        new AgentEvent(
            "start",
            0,
            start,
            AgentEventType.AGENT_RUN_STARTED,
            1,
            "session",
            null,
            "run",
            null,
            null,
            new AgentRunStartedPayload("run", null, null),
            project.id()));
    eventStore.append(
        new AgentEvent(
            "failed",
            0,
            start.plusSeconds(5),
            AgentEventType.AGENT_RUN_FAILED,
            1,
            "session",
            null,
            "run",
            null,
            null,
            new AgentRunFailedPayload("run", new ErrorInformation("exception", "reason", "error")),
            project.id()));
    var router = mock(ConversationInputRouter.class);
    var turns = mock(ConversationTurnStore.class);
    when(turns.findRun("session", "run"))
        .thenReturn(
            Optional.of(
                new ConversationTurnStore.Turn(
                    "run", "Original request", ConversationTurnStore.Status.FAILED, null, start)));
    var checkpoints = mock(PersistentCheckpointService.class);
    var registry = new StaticListableBeanFactory().getBeanProvider(RunRegistry.class);
    eventStore.append(completed("successful", project.id(), 20));
    var restarted =
        new RunManagementService(
            projects,
            router,
            new SqliteProjectAgentEventStore(directory),
            turns,
            checkpoints,
            registry);
    var rows = restarted.runs();
    assertEquals(1, rows.size());
    assertEquals("FAILED", rows.getFirst().status());
    assertEquals("Original request", rows.getFirst().request());
    assertEquals("reason", rows.getFirst().failure());
    assertEquals(start.toString(), rows.getFirst().startedAt());
    assertTrue(restarted.checkpoints(project.id(), "run").isEmpty());
    assertEquals("successful", restarted.history(project.id(), "completed", 3).getFirst().runId());
    assertEquals("run", restarted.history(project.id(), "failed", 3).getFirst().runId());
  }
}
