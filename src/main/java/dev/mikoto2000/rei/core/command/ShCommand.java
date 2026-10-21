package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.SystemShellService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** 現在の作業ディレクトリでシステムシェルを開きますを行うための利用者コマンド。 */
@Component
@Command(name = "sh", description = "現在の作業ディレクトリでシステムシェルを開きます")
public class ShCommand implements Runnable {

  /** システムシェルサービス。 */
  private final SystemShellService systemShellService;

  /** 現在ディレクトリ取得元。 */
  private final Supplier<Path> currentDirectorySupplier;

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public ShCommand(SystemShellService systemShellService, ProjectService projectService) {
    this(systemShellService, projectService::currentProject);
  }

  /** 指定された値や利用先を使って、ShCommandを初期化する。 */
  ShCommand(SystemShellService systemShellService, Supplier<Path> currentDirectorySupplier) {
    this.systemShellService = systemShellService;
    this.currentDirectorySupplier = currentDirectorySupplier;
  }

  /** 現在の作業ディレクトリでシステムシェルを開きますを行う。 */
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
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new IllegalStateException("システムシェルが中断されました", e);
    } catch (IOException e) {
      throw new IllegalStateException("システムシェルの起動に失敗しました", e);
    }
  }

  /** 要求された処理を利用できるよう、プロセスを開始する。 */
  Process startProcess(List<String> commandLine, Path directory) throws IOException {
    return new ProcessBuilder(commandLine).directory(directory.toFile()).inheritIO().start();
  }
}
