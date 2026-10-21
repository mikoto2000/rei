package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.checkpoint.CheckpointProperties;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.web.ApprovalController;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 承認表示用の情報の結果と状態の契約を検証するテスト。 */
class ApprovalPresentationTest {
  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellNumbersStayBoundToTheDisplayedRequestAndContinueIsExplicit() {
    var repository = mock(ToolApprovalRepository.class);
    var projects = mock(ProjectService.class);
    var continuations = mock(ApprovalContinuationService.class);
    String project = UUID.randomUUID().toString();
    when(projects.currentContext())
        .thenReturn(new ProjectContext(project, "project", Path.of(".")));
    var first =
        new ToolApprovalRepository.Request(
            "first",
            project,
            "session",
            "run",
            "runCommand",
            "npm run textlint",
            "PENDING",
            Instant.now().plusSeconds(600));
    var second =
        new ToolApprovalRepository.Request(
            "second",
            project,
            "session",
            "run-2",
            "runCommand",
            "npm run build",
            "PENDING",
            Instant.now().plusSeconds(600));
    when(continuations.list(project)).thenReturn(java.util.List.of(first, second));
    when(continuations.state(anyString())).thenReturn(Optional.of("WAITING_APPROVAL"));
    when(repository.get(project, "first")).thenReturn(first);
    when(continuations.decide(project, "first", true, true)).thenReturn(first);
    var command = new ApprovalCommand(repository, projects);
    command.setContinuations(continuations);
    var output = new StringWriter();
    var cli = new CommandLine(command);
    cli.setOut(new PrintWriter(output));
    assertEquals(0, cli.execute("list"));
    assertTrue(output.toString().contains("/approval approve 1 --continue"));
    when(continuations.list(project)).thenReturn(java.util.List.of(second));
    assertEquals(0, cli.execute("list"));
    assertEquals(0, cli.execute("show", "1"));
    verify(repository).get(project, "first");
    assertEquals(0, cli.execute("approve", "1", "--continue"));
    verify(continuations).decide(project, "first", true, true);
    verify(repository, never()).decide(anyString(), anyString(), anyBoolean());
    String other = UUID.randomUUID().toString();
    when(projects.currentContext()).thenReturn(new ProjectContext(other, "other", Path.of(".")));
    assertEquals(2, cli.execute("show", "1"));
    verify(repository, never()).get(other, "first");
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void showAndApprovePrintTheTaskIdInsteadOfApprovalOrRunId() {
    var repository = mock(ToolApprovalRepository.class);
    var projects = mock(ProjectService.class);
    var checkpoints = mock(PersistentCheckpointRepository.class);
    String project = UUID.randomUUID().toString();
    when(projects.currentContext())
        .thenReturn(new ProjectContext(project, "project", Path.of(".")));
    var request =
        new ToolApprovalRepository.Request(
            "approval-id",
            project,
            "session",
            "old-run",
            "runCommand",
            "{}",
            "APPROVED",
            Instant.now().plusSeconds(600));
    var state = mock(PersistentCheckpoint.class);
    when(state.taskId()).thenReturn("task-id");
    when(state.sessionId()).thenReturn("session");
    when(state.status()).thenReturn("FAILED");
    when(repository.get(project, "approval-id")).thenReturn(request);
    when(repository.decide(project, "approval-id", true)).thenReturn(request);
    when(checkpoints.findByRun(project, "old-run")).thenReturn(Optional.of(state));
    var command = new ApprovalCommand(repository, projects);
    command.setCheckpoints(checkpoints);
    var output = new StringWriter();
    var cli = new CommandLine(command);
    cli.setOut(new PrintWriter(output));
    assertEquals(0, cli.execute("show", "approval-id"));
    assertTrue(output.toString().contains("task ID: task-id"));
    assertTrue(output.toString().contains("再開コマンド: /resume task-id"));
    output.getBuffer().setLength(0);
    assertEquals(0, cli.execute("approve", "approval-id"));
    assertTrue(output.toString().contains("task ID: task-id"));
    assertTrue(output.toString().contains("再開コマンド: /resume task-id"));
    assertFalse(output.toString().contains("/resume approval-id"));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void missingCheckpointStillReportsSuccessfulApprovalWithLookupInstructions() {
    var repository = mock(ToolApprovalRepository.class);
    var projects = mock(ProjectService.class);
    String project = UUID.randomUUID().toString();
    when(projects.currentContext())
        .thenReturn(new ProjectContext(project, "project", Path.of(".")));
    when(repository.decide(project, "approval-id", true))
        .thenReturn(
            new ToolApprovalRepository.Request(
                "approval-id",
                project,
                "session",
                "run",
                "runCommand",
                "{}",
                "APPROVED",
                Instant.now()));
    var output = new StringWriter();
    var cli = new CommandLine(new ApprovalCommand(repository, projects));
    cli.setOut(new PrintWriter(output));
    assertEquals(0, cli.execute("approve", "approval-id"));
    assertTrue(output.toString().contains("APPROVED"));
    assertTrue(output.toString().contains("/resume list"));
    assertFalse(output.toString().contains("/resume approval-id"));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  void approvalFromAnEarlierResumeResolvesThroughCheckpointHistory(
      @org.junit.jupiter.api.io.TempDir Path directory) {
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("checkpoints.db"));
    var checkpoints = new PersistentCheckpointRepository(source, new CheckpointProperties());
    var initial =
        checkpoints.save(
            PersistentCheckpoint.initial(
                "task-id", "project", "session", "original-run", directory, "review"),
            0,
            "initial");
    var resumed =
        checkpoints.save(initial.resume("approval-run"), initial.revision(), "first-resume");
    var latest = checkpoints.save(resumed.resume("new-run"), resumed.revision(), "second-resume");
    assertEquals(latest, checkpoints.findByRun("project", "approval-run").orElseThrow());
    assertTrue(checkpoints.findByRun("other-project", "approval-run").isEmpty());
  }

  /** シェル判断結果一覧について、選択中プロジェクトを利用することを確認する。 */
  @Test
  void shellDecisionsUseSelectedProjectAndRequireAnId() {
    var repository = mock(ToolApprovalRepository.class);
    var projects = mock(ProjectService.class);
    String project = UUID.randomUUID().toString();
    when(projects.currentContext())
        .thenReturn(new ProjectContext(project, "project", Path.of(".")));
    var command = new CommandLine(new ApprovalCommand(repository, projects));
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute("approve", "request"));
    verify(repository).decide(project, "request", true);
    assertEquals(0, command.execute("deny", "other"));
    verify(repository).decide(project, "other", false);
    assertEquals(2, command.execute("approve"));
    verify(repository, times(2)).decide(anyString(), anyString(), anyBoolean());
  }

  /** Web判断結果について、明示的な真偽値を必要とすることを確認する。 */
  @Test
  void webDecisionRequiresExplicitBooleanAndDoesNotDispatch() {
    var repository = mock(ToolApprovalRepository.class);
    var controller = new ApprovalController(repository);
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> controller.decide("p", "id", new ApprovalController.Decision(null)));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(repository);
    controller.decide("p", "id", new ApprovalController.Decision(true));
    verify(repository).decide("p", "id", true);
  }
}
