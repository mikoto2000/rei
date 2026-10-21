package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * 複数ファイルの変更提案を確認し、管理対象のテキストやステージングを明示的に復旧しますを行うための利用者コマンド。
 *
 * <p>Human recovery stays inside the existing captured exclusive conversation boundary.
 */
@Component
@Command(name = "document", description = "複数ファイルの変更提案を確認し、管理対象のテキストやステージングを明示的に復旧します")
public final class DocumentCommand implements java.util.concurrent.Callable<Integer> {
  /** 会話一覧。 */
  private final ShellConversationService conversations;

  /** 操作。 */
  @Parameters(
      index = "0",
      paramLabel = "show|rollback|clean|reconcile-single",
      completionCandidates = ActionCandidates.class)
  String action;

  /** 識別子。 */
  @Parameters(index = "1", paramLabel = "ID")
  String id;

  /** ハッシュ値。 */
  @Parameters(index = "2", arity = "0..1", paramLabel = "PROPOSAL_SHA256")
  String hash;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DocumentCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、DocumentCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public DocumentCommand(ShellConversationService conversations) {
    this.conversations = conversations;
  }

  /** 複数ファイルの変更提案を確認し、管理対象のテキストやステージングを明示的に復旧しますを行う。 */
  @Override
  public Integer call() {
    if (conversations == null
        || id == null
        || !id.matches("[a-f0-9-]{36}")
        || !(action.equals("show") && hash == null
            || java.util.Set.of("rollback", "clean", "reconcile-single").contains(action)
                && hash != null
                && hash.matches("[a-f0-9]{64}"))) {
      spec.commandLine()
          .getErr()
          .println(
              "Use /document show UUID or /document rollback|clean|reconcile-single UUID proposalSha256");
      return 2;
    }
    conversations.submit("/document " + action + " " + id + (hash == null ? "" : " " + hash));
    return 0;
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super("show", "rollback", "clean", "reconcile-single");
    }
  }
}
