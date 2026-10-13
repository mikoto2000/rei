package dev.mikoto2000.rei.ui.shell;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import picocli.CommandLine.Command;

@Component
@Command(name = "cancel", description = "実行中の Agent をキャンセルします")
public class CancelCommand implements Runnable {
  private final CommandCancellationService cancellation;
  public CancelCommand() { this(null); }
  @org.springframework.beans.factory.annotation.Autowired
  public CancelCommand(CommandCancellationService cancellation) { this.cancellation = cancellation; }
  @picocli.CommandLine.Spec private picocli.CommandLine.Model.CommandSpec spec;
  public void run() {
    if (cancellation == null) throw new IllegalStateException("Cancellation runtime is unavailable");
    var out = spec.commandLine().getOut();
    out.println(cancellation.cancelCurrentProject()
        ? "[cancel] ユーザーの要求により停止を要求しました。終了通知を確認してください。"
        : "[cancel] 停止対象の実行がないか、既に停止を要求済みです。");
    out.flush();
  }
}
