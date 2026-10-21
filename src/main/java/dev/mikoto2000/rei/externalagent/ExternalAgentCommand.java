package dev.mikoto2000.rei.externalagent;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * 外部エージェントのレビュー・分離実装の結果を確認し、検証済みの結果を明示的にマージしますを行うための利用者コマンド。
 *
 * <p>Thin asynchronous shell adapter. The external advisor dispatches the shared domain service.
 */
@Component
@Command(name = "agent", description = "外部エージェントのレビュー・分離実装の結果を確認し、検証済みの結果を明示的にマージします")
public class ExternalAgentCommand implements java.util.concurrent.Callable<Integer> {
  /** 会話一覧。 */
  private final ShellConversationService conversations;

  /** 引数。 */
  @Parameters(
      arity = "0..*",
      paramLabel = "AGENT ACTION [TARGET]",
      completionCandidates = ExternalAgentCompletionCandidates.class)
  private String[] arguments;

  /** 仕様。 */
  @Spec private picocli.CommandLine.Model.CommandSpec spec;

  /** 外部エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ExternalAgentCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、ExternalAgentCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ExternalAgentCommand(ShellConversationService conversations) {
    this.conversations = conversations;
  }

  /** 外部エージェントのレビュー・分離実装の結果を確認し、検証済みの結果を明示的にマージしますを行う。 */
  @Override
  public Integer call() {
    String text = "/agent" + (arguments == null ? "" : " " + String.join(" ", arguments));
    try {
      ExternalAgentCommandRequest.parse(text);
    } catch (IllegalArgumentException error) {
      spec.commandLine().getErr().println(dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    }
    if (conversations == null) {
      spec.commandLine().getErr().println("External agent runtime unavailable");
      return 2;
    }
    conversations.submit(text);
    return 0;
  }
}
