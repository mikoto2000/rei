package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.command.ProjectCommand;
import dev.mikoto2000.rei.core.project.ProjectClient;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine;

/**
 * ターミナルでの対話に必要なシェルプロジェクトコマンド一覧を扱う。
 *
 * <p>Shell-only binding and restoration, including commands dispatched to a worker thread.
 */
public final class ShellProjectCommands {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ShellProjectCommands() {}

  /** ターミナルでの対話に関する設定を処理する。 */
  public static void configure(
      CommandLine command,
      ProjectService projects,
      ProjectClient client,
      ProjectShellActivity activity) {
    installBusyHandler(command);
    var delegate = command.getExecutionStrategy();
    command.setExecutionStrategy(
        parsed -> {
          try (var scope = client.open()) {
            int result = delegate.execute(parsed);
            var leaf = parsed;
            while (leaf.hasSubcommand()) leaf = leaf.subcommand();
            if (result == 0
                && !parsed.isUsageHelpRequested()
                && !parsed.isVersionHelpRequested()
                && !leaf.isUsageHelpRequested()
                && !leaf.isVersionHelpRequested()
                && leaf.commandSpec().userObject() instanceof ProjectCommand.CdCommand
                && activity != null) activity.restore(projects.currentContext());
            else if (activity != null) activity.refreshSession();
            return result;
          }
        });
  }

  /** ターミナルでの対話に関するinstall使用中受付後の処理を処理する。 */
  public static void installBusyHandler(CommandLine command) {
    command.setExecutionExceptionHandler(
        (error, line, parsed) -> {
          if (!(error instanceof dev.mikoto2000.rei.application.run.SessionBusyException busy)) {
            boolean cancelled =
                dev.mikoto2000.rei.core.chat.RunCancellation.isCancellation(error)
                    || Thread.currentThread().isInterrupted();
            line.getErr()
                .println(
                    (cancelled ? "[cancelled] " : "[error] ")
                        + line.getCommandSpec().qualifiedName()
                        + ": "
                        + dev.mikoto2000.rei.event.FailureMessages.describe(error));
            line.getErr().flush();
            return cancelled
                ? 130
                : line.getExitCodeExceptionMapper() == null
                    ? line.getCommandSpec().exitCodeOnExecutionException()
                    : line.getExitCodeExceptionMapper().getExitCode(error);
          }
          var out = line.getErr();
          out.println("新しい依頼は受け付けていません。同じ Session に既存の Run が残っています。");
          out.println(
              "Run ID: "
                  + busy.runId()
                  + " / 状態: "
                  + (busy.status() == null ? "不明" : busy.status()));
          if ("WAITING_APPROVAL".equals(busy.status())) {
            out.println("既存の作業を続ける場合:");
            out.println("  1. /approval list で要求を確認してください。");
            out.println("  2. 対象の要求を /approval show <承認ID> で確認してください。");
            out.println("  3. /approval approve <承認ID> --continue で承認して続行してください。");
            out.println("既存の作業を取りやめる場合:");
            out.println("  /approval deny <承認ID> で拒否し、停止処理が終わってから新しい依頼を送ってください。");
            out.println("承認待ちは実行中件数に含まれないため、0 running でもこの状態になります。");
          } else if ("WAITING".equals(busy.status())) {
            out.println("既存の作業は外部応答待ちです。/resume list でタスクを確認し、必要な応答を返すか、応答が届くのを待ってください。");
          } else {
            out.println("/runs で既存の作業を確認し、終了してから依頼を再送してください。");
          }
          out.println("既存の作業を残して別の依頼を始める場合: /new で別 Session を作成し、依頼を送ってください。");
          out.println("Run ID は承認IDや task ID とは異なります。上の <承認ID> は /approval list のIDに置き換えてください。");
          out.flush();
          return 2;
        });
  }
}
