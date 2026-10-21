package dev.mikoto2000.rei.paper;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

/** 論文検索・要約・翻訳・Library: search/show/summarize/translate/library/importを行うための利用者コマンド。 */
@Component
@Command(
    name = "paper",
    description = "論文検索・要約・翻訳・Library: search/show/summarize/translate/library/import")
public class PaperCommand implements java.util.concurrent.Callable<Integer> {
  /** 会話一覧。 */
  private final dev.mikoto2000.rei.application.session.ShellConversationService conversations;

  /** 引数。 */
  @Unmatched String[] arguments;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 論文の調査と整理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public PaperCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、PaperCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public PaperCommand(
      dev.mikoto2000.rei.application.session.ShellConversationService conversations) {
    this.conversations = conversations;
  }

  /** 論文検索・要約・翻訳・Library: search/show/summarize/translate/library/importを行う。 */
  public Integer call() {
    try {
      String[] args = arguments == null ? new String[0] : arguments;
      PaperCommandRequest.parse(args);
      if (conversations == null) {
        spec.commandLine().getErr().println("Paper runtime unavailable");
        return 2;
      }
      conversations.submit(
          "/paper "
              + java.util.Arrays.stream(args)
                  .map(
                      a -> dev.mikoto2000.rei.core.command.UserInputParser.quote(a, true, (char) 0))
                  .collect(java.util.stream.Collectors.joining(" ")));
      return 0;
    } catch (PaperException e) {
      spec.commandLine().getErr().println(dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return 2;
    }
  }
}
