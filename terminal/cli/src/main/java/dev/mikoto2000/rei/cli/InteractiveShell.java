package dev.mikoto2000.rei.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jline.terminal.Terminal;

/** Explicit user shell command, executed locally in the selected Project. */
final class InteractiveShell {
  static List<String> command(Map<String, String> environment, String osName) {
    String shell = environment.get("SHELL");
    if (shell == null || shell.isBlank()) shell = osName.toLowerCase(Locale.ROOT).contains("win") ? "powershell" : "bash";
    String name = Path.of(shell).getFileName().toString().toLowerCase(Locale.ROOT);
    return List.of("powershell", "powershell.exe", "pwsh", "pwsh.exe").contains(name)
        ? List.of(shell, "-NoLogo") : List.of(shell);
  }

  static int run(Terminal terminal, Path directory) throws IOException, InterruptedException {
    if (!java.nio.file.Files.isDirectory(directory)) throw new IllegalArgumentException("Project directory is unavailable");
    var attributes = terminal.getAttributes();
    Process child = null;
    try {
      terminal.flush();
      terminal.pause();
      child = new ProcessBuilder(command(System.getenv(), System.getProperty("os.name")))
          .directory(directory.toFile()).inheritIO().start();
      return child.waitFor();
    } finally {
      if (child != null && child.isAlive()) child.destroy();
      terminal.setAttributes(attributes);
      terminal.resume();
    }
  }
}
