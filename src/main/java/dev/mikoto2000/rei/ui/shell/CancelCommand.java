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

  /** 仕様。 */
  @picocli.CommandLine.Spec private picocli.CommandLine.Model.CommandSpec spec;

  /** 実行中の Agent をキャンセルしますを行う。 */
  public void run() {
    if (cancellation == null)
      throw new IllegalStateException("Cancellation runtime is unavailable");
    var out = spec.commandLine().getOut();
    out.println(
        cancellation.cancelCurrentProject()
            ? "[cancel] ユーザーの要求により停止を要求しました。終了通知を確認してください。"
            : "[cancel] 停止対象の実行がないか、既に停止を要求済みです。");
    out.flush();
  }
}
