package dev.mikoto2000.rei.storage;

import dev.mikoto2000.rei.core.command.StorageCommand;
import java.io.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import static org.assertj.core.api.Assertions.*;

class StorageCommandTest {
  @Test void rootRegistersStorage() {
    assertThat(new CommandLine(new dev.mikoto2000.rei.ui.shell.RootCommand()).getSubcommands())
        .containsKey("storage");
  }
  @Test void absentProjectDoesNotSilentlyScanGlobalStorage() {
    var error = new StringWriter();
    var command = new CommandLine(new StorageCommand());
    command.setErr(new PrintWriter(error));
    command.setOut(new PrintWriter(new StringWriter()));
    assertThat(command.execute("status", "--project", "current")).isEqualTo(2);
    assertThat(error.toString()).contains("No current project");
  }
  @Test void invalidScopeIsRejected() {
    var command = new CommandLine(new StorageCommand());
    command.setErr(new PrintWriter(new StringWriter()));
    assertThat(command.execute("status", "--project", "all")).isEqualTo(2);
  }
}
