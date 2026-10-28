package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Verified bytes can replace a confirmation read, but never authorize a rebase or imply valid code.
 */
class WriteVerificationTest {
  @TempDir Path root;
  private final String projectId = "00000000-0000-0000-0000-000000000001";

  @Test
  void resultAndOperationCollectionsCannotBeMutatedThroughInputAliases() {
    var ranges = new ArrayList<TextDiff.Range>();
    ranges.add(new TextDiff.Range(1, 2, 1, 2));
    var summary = new TextDiff.FileSummary("a.txt", 1, 1, 1, ranges);
    ranges.clear();
    assertEquals(1, summary.ranges().size());
    assertThrows(UnsupportedOperationException.class, () -> summary.ranges().clear());
    var edits = new ArrayList<TextChangeSetService.Edit>();
    edits.add(new TextChangeSetService.Edit("a", "b"));
    var operation =
        new TextDocumentChangeSetService.Operation(
            "UPDATE", "a.txt", null, null, null, "sha", edits);
    edits.clear();
    assertEquals(1, operation.edits().size());
  }

  private TextChangeSetRepository repository() {
    return new TextChangeSetRepository(
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("db")));
  }

  @Test
  void staleApprovalNeverWritesAndErrorCanReachModelWithoutSuccessfulEvent() throws Exception {
    Files.writeString(root.resolve("a.txt"), "old\n");
    var service = new TextChangeSetService(repository());
    var project = new ProjectContext(projectId, "test", root);
    var proposal =
        service.propose(project, new TextChangeSetService.Request("a.txt", "old\n", "new\n"));
    Files.writeString(root.resolve("a.txt"), "external\n");
    var stale =
        service.apply(
            project,
            proposal.id(),
            proposal.proposalSha256(),
            (p, a, b) -> {
              throw new AssertionError("old approval cannot authorize new bytes");
            });
    assertEquals("VERSION_MISMATCH", stale.error().code());
    assertFalse(stale.error().safeToReplay());
    assertEquals("external\n", Files.readString(root.resolve("a.txt")));
    var failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.propose(
                    project,
                    new TextChangeSetService.Request(
                        "a.txt",
                        null,
                        null,
                        proposal.baselineSha256(),
                        List.of(new TextChangeSetService.Edit("external", "bad")))));
    var definition = mock(ToolDefinition.class);
    when(definition.name()).thenReturn("proposeTextChangeSet");
    var callback = mock(ToolCallback.class);
    when(callback.getToolDefinition()).thenReturn(definition);
    when(callback.call("input")).thenThrow(new RuntimeException(failure));
    var bus = new InMemoryAgentEventBus();
    var events = new ArrayList<AgentEvent>();
    bus.subscribe(events::add);
    String result =
        new ToolEventCallbackDecorator(callback, new AgentEventFactory(Clock.systemUTC()), bus)
            .call("input");
    assertEquals(failure.getMessage(), result);
    assertEquals(
        List.of(AgentEventType.TOOL_STARTED, AgentEventType.TOOL_FAILED),
        events.stream().map(AgentEvent::type).toList());
  }

  @Test
  void batchReceiptIsCompactAndLostResponseDoesNotRepublishOrReauthorizeChangedFiles()
      throws Exception {
    Files.writeString(root.resolve("a.txt"), "old\n");
    var service = new TextDocumentChangeSetService(repository(), Clock.systemUTC());
    var owner = new AgentRunContext("run", "session", root, projectId);
    var request =
        new TextDocumentChangeSetService.Request(
            List.of(
                new TextDocumentChangeSetService.Operation(
                    "UPDATE", "a.txt", null, "old\n", "new\n")));
    var proposal = service.propose(owner, request);
    var applied = service.apply(owner, proposal.id(), proposal.proposalSha256());
    assertTrue(applied.currentMatches());
    assertEquals("", applied.diff());
    assertEquals(1, applied.changeCount());
    assertEquals(1, applied.changedFiles());
    assertEquals(1, applied.changes().getFirst().ranges().size());
    Files.writeString(root.resolve("a.txt"), "external");
    var restarted = new TextDocumentChangeSetService(repository(), Clock.systemUTC());
    assertFalse(restarted.apply(owner, proposal.id(), proposal.proposalSha256()).currentMatches());
    assertEquals("external", Files.readString(root.resolve("a.txt")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            restarted.diffPage(
                new AgentRunContext("run", "another-session", root, projectId),
                proposal.id(),
                proposal.proposalSha256(),
                0,
                256));
  }

  @Test
  void encodingAndIndependentSizeLimitsHaveTypedFailures() throws Exception {
    var service = new TextChangeSetService(repository());
    var project = new ProjectContext(projectId, "test", root);
    Files.write(root.resolve("a.txt"), new byte[] {(byte) 0xff});
    var encoding =
        assertThrows(IllegalArgumentException.class, () -> service.readBase(project, "a.txt"));
    assertTrue(encoding.getMessage().contains("UNSUPPORTED_ENCODING"));
    Files.writeString(root.resolve("a.txt"), "x".repeat(1048577));
    var size =
        assertThrows(IllegalArgumentException.class, () -> service.readBase(project, "a.txt"));
    assertTrue(size.getMessage().contains("SIZE_LIMIT_EXCEEDED"));
    Files.writeString(root.resolve("a.txt"), "old");
    var request =
        new TextChangeSetService.Request(
            "a.txt",
            null,
            null,
            TextDocumentTransaction.hash("old"),
            List.of(new TextChangeSetService.Edit("old", "x".repeat(65536))));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> service.propose(project, request))
            .getMessage()
            .contains("SIZE_LIMIT_EXCEEDED"));
    assertEquals("old", Files.readString(root.resolve("a.txt")));
  }

  @Test
  void promptExplainsVerifiedSaveAndNecessarySemanticChecks() throws Exception {
    String prompt = Files.readString(Path.of("src/main/resources/system-prompt.md"));
    assertTrue(prompt.contains("currentMatches"));
    assertTrue(prompt.contains("構文"));
    assertTrue(prompt.contains("UNKNOWN"));
  }
}
