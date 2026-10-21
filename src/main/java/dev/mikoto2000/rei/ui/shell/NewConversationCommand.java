package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** 新しい Session を作成して選択しますを行うための利用者コマンド。 */
@Component
@Command(name = "new", description = "新しい Session を作成して選択します", mixinStandardHelpOptions = true)
public final class NewConversationCommand implements java.util.concurrent.Callable<Integer> {
  /** 会話一覧。 */
  private final ShellConversationService conversations;

  /** 仕様。 */
  @Spec private picocli.CommandLine.Model.CommandSpec spec;

  /** タイトル。 */
  @Parameters(arity = "0..1", paramLabel = "TITLE")
  String title;

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public NewConversationCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、NewConversationCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public NewConversationCommand(ShellConversationService conversations) {
    this.conversations = conversations;
  }

  /** 新しい Session を作成して選択しますを行う。 */
  @Override
  public Integer call() {
    try {
      var session = conversations.newConversation(title);
      spec.commandLine().getOut().println("Current session: " + session.sessionId());
      return 0;
    } catch (RuntimeException error) {
      spec.commandLine()
          .getErr()
          .println(
              "Cannot create session."
                  + " 原因: "
                  + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 1;
    }
  }
}
