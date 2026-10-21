package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * 診断済みの修復を確認し、保存された修復記録のハッシュを指定して明示的に承認しますを行うための利用者コマンド。
 *
 * <p>Explicit human instruction enters the existing exclusive conversation queue. No model-supplied
 * approval.
 */
@Component
@Command(name = "repair", description = "診断済みの修復を確認し、保存された修復記録のハッシュを指定して明示的に承認します")
public final class RepairCommand implements java.util.concurrent.Callable<Integer> {
  /** 会話一覧。 */
  private final ShellConversationService conversations;

  /** 操作。 */
  @Parameters(index = "0", paramLabel = "show|apply", completionCandidates = ActionCandidates.class)
  String action;

  /** 識別子。 */
  @Parameters(index = "1", paramLabel = "ID")
  String id;

  /** ハッシュ値。 */
  @Parameters(index = "2", arity = "0..1", paramLabel = "RECEIPT_SHA256")
  String hash;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RepairCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、RepairCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public RepairCommand(ShellConversationService conversations) {
    this.conversations = conversations;
  }

  /** 診断済みの修復を確認し、保存された修復記録のハッシュを指定して明示的に承認しますを行う。 */
  @Override
  public Integer call() {
    if (conversations == null
        || id == null
        || !id.matches("[0-9a-fA-F-]{36}")
        || !(action.equals("show") && hash == null
            || action.equals("apply") && hash != null && hash.matches("[a-f0-9]{64}"))) {
      spec.commandLine()
          .getErr()
          .println("Use /repair show UUID or /repair apply UUID receiptSha256");
      return 2;
    }
    conversations.submit("/repair " + action + " " + id + (hash == null ? "" : " " + hash));
    return 0;
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super("show", "apply");
    }
  }
}
