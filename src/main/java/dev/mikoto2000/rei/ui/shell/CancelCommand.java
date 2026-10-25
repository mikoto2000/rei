package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** 実行中の Agent をキャンセルしますを行うための利用者コマンド。 */
@Component
@Command(name = "cancel", description = "実行中の Agent をキャンセルします")
public class CancelCommand implements Runnable {
  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public CancelCommand() {
    this(null);
  }

  /** 指定された値や利用先を使って、CancelCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public CancelCommand(CommandCancellationService cancellation) {
    this.cancellation = cancellation;
  }

  /** 省略時は選択中 Project、指定時はその Run だけを停止する。 */
  @picocli.CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "RUN_ID")
  private String runId;

  /** 仕様。 */
  @picocli.CommandLine.Spec private picocli.CommandLine.Model.CommandSpec spec;

  /** 実行中の Agent をキャンセルしますを行う。 */
  public void run() {
    if (cancellation == null)
      throw new IllegalStateException("Cancellation runtime is unavailable");
    var out = spec.commandLine().getOut();
    if (runId != null) {
      var before = cancellation.diagnostic(runId);
      if (before.status().equals("NOT_FOUND")) out.println("[cancel] 対象 Run は存在しません: " + runId);
      else if (before.status().equals("FINISHED")) out.println("[cancel] Run は既に終了しています: " + runId);
      else {
        boolean accepted = cancellation.cancelRegisteredRun(runId);
        out.println(
            accepted ? "[cancel] 停止を要求しました: " + runId : "[cancel] 既に停止要求済みで、終了を待っています: " + runId);
        printDiagnostic(out, cancellation.diagnostic(runId));
      }
    } else {
      boolean accepted = cancellation.cancelCurrentProject();
      var diagnostics = cancellation.currentProjectDiagnostics();
      out.println(
          accepted
              ? "[cancel] ユーザーの要求により停止を要求しました。終了通知を確認してください。"
              : diagnostics.isEmpty()
                  ? "[cancel] 停止対象の実行がない状態です。"
                  : "[cancel] 既に停止要求済みで、終了を待っています。");
      diagnostics.forEach(diagnostic -> printDiagnostic(out, diagnostic));
    }
    out.flush();
  }

  /** 停止未完了や失敗は終了と区別し、ID と安全な原因コードを表示する。 */
  private void printDiagnostic(
      java.io.PrintWriter out, CommandCancellationService.Diagnostic diagnostic) {
    out.println(
        "[cancel] run="
            + diagnostic.runId()
            + " status="
            + diagnostic.status()
            + " requestedAt="
            + diagnostic.requestedAt()
            + (diagnostic.status().equals("CANCEL_STALLED") ? " 停止要求済みですが、処理が終了できていません。" : "")
            + (diagnostic.failure() == null ? "" : " 停止処理の失敗: " + diagnostic.failure()));
  }
}
