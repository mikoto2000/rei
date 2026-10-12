package dev.mikoto2000.rei;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.jline.terminal.Terminal;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.utils.NonBlockingReader;

/** Foreground Backend lifetime follows its console; detached Backends do not use this reader. */
final class ForegroundConsole {
  static final String PROPERTY="rei.console-exit.enabled";
  private ForegroundConsole() {}
  static void run(org.springframework.context.ConfigurableApplicationContext context)throws IOException {
    try(var terminal=ReiApplication.terminalBuilder(System.out).build()) {
      awaitExit(terminal,context::isActive);
    }finally {context.close();}
  }
  static int readCommandInput(NonBlockingReader reader,long timeout)throws IOException {
    int input=reader.read(timeout);
    if(input==3)throw new UserInterruptException("");
    if(input==4||input==-1)throw new EndOfFileException();
    return input;
  }
  static void awaitExit(Terminal terminal, BooleanSupplier running) throws IOException {
    var interrupted=new AtomicBoolean();
    var priorHandler=terminal.handle(Terminal.Signal.INT,signal->interrupted.set(true));
    var attributes=terminal.enterRawMode();
    try {
      terminal.writer().println("Backend started. Ctrl+D / Ctrl+C to stop.");terminal.writer().flush();
      while(running.getAsBoolean()&&!interrupted.get()) {
        int input=terminal.reader().read(200);
        if(input==-1||input==3||input==4)break;
      }
    } finally {
      terminal.setAttributes(attributes);terminal.handle(Terminal.Signal.INT,priorHandler);
    }
  }
}
