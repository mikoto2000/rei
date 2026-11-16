package dev.mikoto2000.rei.application.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
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
  void persistedFailureWithoutCheckpointIsListedAfterRestart() throws Exception {
    try (var migration = new StorageMigrationCoordinator(directory)) {
      migration.prepare();
    }
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
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
    var restarted =
        new RunManagementService(
            projects, router, new SqliteProjectAgentEventStore(directory), turns, checkpoints, registry);
    var rows = restarted.runs();
    assertEquals(1, rows.size());
    assertEquals("FAILED", rows.getFirst().status());
    assertEquals("Original request", rows.getFirst().request());
    assertEquals("reason", rows.getFirst().failure());
    assertEquals(start.toString(), rows.getFirst().startedAt());
    assertTrue(restarted.checkpoints(project.id(), "run").isEmpty());
  }
}
