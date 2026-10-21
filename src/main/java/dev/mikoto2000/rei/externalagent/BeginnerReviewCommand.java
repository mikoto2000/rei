package dev.mikoto2000.rei.externalagent;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/**
 * 初学者視点の静的教材レビュー: beginner --root DIR --entry PAGEを行うための利用者コマンド。
 *
 * <p>Thin adapter over the existing project/session queue.
 */
@Component
@Command(name = "material-review", description = "初学者視点の静的教材レビュー: beginner --root DIR --entry PAGE")
public class BeginnerReviewCommand implements java.util.concurrent.Callable<Integer> {
  /** 会話一覧。 */
  private final dev.mikoto2000.rei.application.session.ShellConversationService conversations;

  /** 引数。 */
  @Unmatched String[] arguments;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public BeginnerReviewCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、BeginnerReviewCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public BeginnerReviewCommand(
      dev.mikoto2000.rei.application.session.ShellConversationService conversations) {
    this.conversations = conversations;
  }

  /** 初学者視点の静的教材レビュー: beginner --root DIR --entry PAGEを行う。 */
  public Integer call() {
    try {
      var args = arguments == null ? new String[0] : arguments;
      BeginnerReviewRequest.parse(args);
      if (conversations == null)
        throw new IllegalArgumentException("Material review runtime unavailable");
      conversations.submit(
          "/material-review "
              + java.util.Arrays.stream(args)
                  .map(
                      a -> dev.mikoto2000.rei.core.command.UserInputParser.quote(a, true, (char) 0))
                  .collect(java.util.stream.Collectors.joining(" ")));
      return 0;
    } catch (IllegalArgumentException error) {
      spec.commandLine().getErr().println(dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    }
  }
}
