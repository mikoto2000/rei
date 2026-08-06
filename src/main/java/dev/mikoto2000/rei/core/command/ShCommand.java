package dev.mikoto2000.rei.core.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.SystemShellService;
import picocli.CommandLine.Command;

@Component
@Command(name = "sh", description = "現在の作業ディレクトリでシステムシェルを開きます")
public class ShCommand implements Runnable {

  private final SystemShellService systemShellService;
  private final Supplier<Path> currentDirectorySupplier;

  @Autowired
  public ShCommand(SystemShellService systemShellService, ProjectService projectService) {
    this(systemShellService, projectService::currentProject);
  }

  ShCommand(SystemShellService systemShellService, Supplier<Path> currentDirectorySupplier) {
    this.systemShellService = systemShellService;
    this.currentDirectorySupplier = currentDirectorySupplier;
  }

  @Override
  public void run() {
    String shell = systemShellService.resolveShell(System.getenv(), System.getProperty("os.name"));
    List<String> commandLine = systemShellService.interactiveShellCommandLine(shell);
    Path currentDirectory = currentDirectorySupplier.get();
    try {
      Process process = startProcess(commandLine, currentDirectory);
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        System.err.println("shell exited with code " + exitCode);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("システムシェルが中断されました", e);
    } catch (IOException e) {
      throw new IllegalStateException("システムシェルの起動に失敗しました", e);
    }
  }

  Process startProcess(List<String> commandLine, Path directory) throws IOException {
    return new ProcessBuilder(commandLine)
        .directory(directory.toFile())
        .inheritIO()
        .start();
  }
}
