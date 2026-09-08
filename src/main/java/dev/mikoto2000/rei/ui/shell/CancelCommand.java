package dev.mikoto2000.rei.ui.shell;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import picocli.CommandLine.Command;

@Component
@Command(name = "cancel", description = "実行中の Agent をキャンセルします")
public class CancelCommand implements Runnable {
  private final CommandCancellationService cancellation;
  public CancelCommand(CommandCancellationService cancellation) { this.cancellation = cancellation; }
  public void run() { cancellation.cancel(); }
}
