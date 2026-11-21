package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 再開の成功・失敗に応じた Shell の入力先を検証する。 */
class ResumeSessionCommandTest {
  /** 会話とプロジェクトを保存する一時ディレクトリ。 */
  @TempDir Path root;

  @Test
  void interactiveSelectionResumesOnlyTheChosenTaskAndCancelDoesNothing() {
    var service = mock(PersistentCheckpointService.class);
    var projects = mock(ProjectService.class);
    var context =
        new dev.mikoto2000.rei.core.project.ProjectContext(
            java.util.UUID.randomUUID().toString(), "project", root);
    when(projects.currentContext()).thenReturn(context);
    var first = mock(PersistentCheckpoint.class);
    var second = mock(PersistentCheckpoint.class);
    when(second.taskId()).thenReturn("second");
    var candidates = java.util.List.of(first, second);
    when(service.list(context.id())).thenReturn(candidates);
    var resume = new ResumeCommand(service, projects);
    var output = new StringWriter();
    var command = new picocli.CommandLine(resume).setOut(new PrintWriter(output));
    when(first.taskId()).thenReturn("first");
    when(second.request()).thenReturn("Bearer PRIVATE_TOKEN\nrequest");
    resume.setTaskMenu((ids, labels) -> {
      assertEquals(java.util.List.of("first", "second"), ids);
      org.junit.jupiter.api.Assertions.assertFalse(labels.get(1).contains("PRIVATE_TOKEN"));
      org.junit.jupiter.api.Assertions.assertFalse(labels.get(1).contains("\n"));
      return java.util.Optional.of(ids.get(1));
    });
    assertEquals(0, command.execute());
    verify(service).resumeForShell(eq(context.id()), eq("second"), any());
    org.mockito.Mockito.clearInvocations(service);
    resume.setTaskSelection(items -> java.util.Optional.empty());
    assertEquals(0, command.execute());
    verify(service, org.mockito.Mockito.never()).resumeForShell(any(), any(), any());
    org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("再開せずに閉じました"));
    when(service.list(context.id())).thenReturn(java.util.List.of());
    resume.setTaskSelection(items -> {
      throw new AssertionError("Empty list must not open a menu");
    });
    assertEquals(0, command.execute());
    org.junit.jupiter.api.Assertions.assertTrue(output.toString().contains("再開候補はありません"));
  }

  /** 再開時には実行開始前に選択し、次の入力もその会話へ送る。 */
  @Test
  void resumeSelectsSessionBeforeSubmissionAndNextInputUsesIt() {
    verifySelection(false, false);
  }

  /** 受付失敗時には元の会話へ戻す。 */
  @Test
  void submissionFailureRestoresPreviousSession() {
    verifySelection(true, false);
  }

  /** 未選択からの再開受付失敗時には未選択へ戻す。 */
  @Test
  void submissionFailureRestoresUnselectedSession() {
    verifySelection(true, true);
  }

  /** 実際のクライアント選択と会話受付を使って切り替えを確認する。 */
  private void verifySelection(boolean fail, boolean unselected) {
    var repository = new FileSessionRepository(root.resolve("sessions.json"));
    var projects =
        new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")), repository);
    var conversations =
        new ShellConversationService(
            projects, new SessionLifecycle(repository, Clock.systemUTC()), (context, prompt) -> {});
    var scope = projects.newClient().open();
    try (scope) {
      String target = conversations.newConversation("target").sessionId();
      String previous = unselected ? null : conversations.newConversation("previous").sessionId();
      projects.selectSession(previous);
      var output = mock(dev.mikoto2000.rei.ui.shell.ShellEventOutput.class);
      var activity =
          new dev.mikoto2000.rei.ui.shell.ProjectShellActivity(
              projects,
              mock(dev.mikoto2000.rei.event.ProjectAgentEventStore.class),
              mock(dev.mikoto2000.rei.core.project.ProjectRunStateStore.class),
              new dev.mikoto2000.rei.core.working.WorkingSet(),
              mock(org.springframework.ai.chat.memory.ChatMemory.class));
      activity.attach(output);
      var service = mock(PersistentCheckpointService.class);
      when(service.resumeForShell(eq(projects.currentContext().id()), eq("task"), any()))
          .thenAnswer(invocation -> {
            Consumer<String> select = invocation.getArgument(2);
            var context =
                new AgentRunContext("resumed", target, root, projects.currentContext().id());
            select.accept(target);
            assertEquals(target, conversations.currentSessionId());
            if (fail) throw new IllegalStateException("Submission rejected");
            var events = new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC());
            activity.onEvent(events.messageStarted("first", "assistant").withOwnership(context));
            activity.onEvent(
                events.messageDelta("first", "first resumed output").withOwnership(context));
            verify(output).print("first resumed output");
            return new PersistentCheckpointService.ResumeResult(
                "task", "resumed", "old", 1, target, null);
          });
      var command =
          new picocli.CommandLine(new ResumeCommand(service, projects))
              .setOut(new PrintWriter(new StringWriter()));
      assertEquals(fail ? 2 : 0, command.execute("task"));
      assertEquals(fail ? previous : target, conversations.currentSessionId());
      if (!fail) assertEquals(target, conversations.submit("follow-up").conversationId());
    }
  }
}
