package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.core.service.SystemShellService;

class ShCommandTest {

  @TempDir
  Path tempDir;

  @Test
  void startsInteractiveSystemShellInCurrentProject() {
    CapturingShCommand command = new CapturingShCommand(new SystemShellService(), tempDir);

    command.run();

    assertThat(command.started).isTrue();
    assertThat(command.commandLine).isEqualTo(new SystemShellService().interactiveShellCommandLine(
        new SystemShellService().resolveShell(System.getenv(), System.getProperty("os.name"))));
    assertThat(command.directory).isEqualTo(tempDir);
  }

  private static class CapturingShCommand extends ShCommand {
    boolean started;
    List<String> commandLine;
    Path directory;

    CapturingShCommand(SystemShellService systemShellService, Path currentProject) {
      super(systemShellService, () -> currentProject);
    }

    @Override
    Process startProcess(List<String> commandLine, Path directory) throws IOException {
      this.started = true;
      this.commandLine = commandLine;
      this.directory = directory;
      return new CompletedProcess();
    }
  }

  private static class CompletedProcess extends Process {
    @Override
    public java.io.OutputStream getOutputStream() {
      return java.io.OutputStream.nullOutputStream();
    }

    @Override
    public java.io.InputStream getInputStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public java.io.InputStream getErrorStream() {
      return java.io.InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
    }
  }
}
