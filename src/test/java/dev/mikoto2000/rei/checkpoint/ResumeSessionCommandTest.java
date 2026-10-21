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
    try (var scope = projects.newClient().open()) {
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
          .thenAnswer(
              invocation -> {
                Consumer<AgentRunContext> select = invocation.getArgument(2);
                var context =
                    new AgentRunContext("resumed", target, root, projects.currentContext().id());
                select.accept(context);
                assertEquals(target, conversations.currentSessionId());
                if (fail) throw new IllegalStateException("Submission rejected");
                var events = new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC());
                activity.onEvent(
                    events.messageStarted("first", "assistant").withOwnership(context));
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
