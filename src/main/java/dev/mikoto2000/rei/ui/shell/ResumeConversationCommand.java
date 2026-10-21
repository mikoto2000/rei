package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.run.SessionConflictException;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** 現在の project の Session を明示的に選択しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "switch",
    aliases = "resume",
    description = "現在の project の Session を明示的に選択します",
    mixinStandardHelpOptions = true)
public final class ResumeConversationCommand implements java.util.concurrent.Callable<Integer> {
  /** 会話一覧。 */
  private final ShellConversationService conversations;

  /** 会話の識別子。 */
  @Parameters(
      index = "0",
      paramLabel = "SESSION_ID",
      completionCandidates = SessionCompletionCandidates.CurrentProject.class)
  String sessionId;

  /** 仕様。 */
  @Spec private picocli.CommandLine.Model.CommandSpec spec;

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ResumeConversationCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、ResumeConversationCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ResumeConversationCommand(ShellConversationService conversations) {
    this.conversations = conversations;
  }

  /** 現在の project の Session を明示的に選択しますを行う。 */
  @Override
  public Integer call() {
    try {
      conversations.resume(sessionId);
      spec.commandLine()
          .getOut()
          .println(
              "Current session: "
                  + new dev.mikoto2000.rei.conversation.HistoryFormatter().label(sessionId));
      return 0;
    } catch (ResourceNotFoundException error) {
      spec.commandLine()
          .getErr()
          .println(
              "Session not found: "
                  + new dev.mikoto2000.rei.conversation.HistoryFormatter().label(sessionId));
      return 2;
    } catch (SessionConflictException error) {
      spec.commandLine()
          .getErr()
          .println("Session belongs to another project. Select that project first.");
      return 2;
    } catch (RuntimeException error) {
      spec.commandLine()
          .getErr()
          .println(
              "Cannot select session."
                  + " 原因: "
                  + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 1;
    }
  }
}
