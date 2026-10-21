package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.service.SystemShellService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shコマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ShCommandTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 対話システムシェルを開始することを確認する。 */
  @Test
  void startsInteractiveSystemShellInCurrentProject() {
    CapturingShCommand command = new CapturingShCommand(new SystemShellService(), tempDir);

    command.run();

    assertThat(command.started).isTrue();
    assertThat(command.commandLine)
        .isEqualTo(
            new SystemShellService()
                .interactiveShellCommandLine(
                    new SystemShellService()
                        .resolveShell(System.getenv(), System.getProperty("os.name"))));
    assertThat(command.directory).isEqualTo(tempDir);
  }

  /** 検証条件を再現するためのCapturingShコマンド。 */
  private static class CapturingShCommand extends ShCommand {
    /** 検証に使う開始。 */
    boolean started;

    /** 検証に使うコマンド行。 */
    List<String> commandLine;

    /** 検証に使うディレクトリ。 */
    Path directory;

    /** 検証条件に必要な値を受け取り、CapturingShCommandを用意する。 */
    CapturingShCommand(SystemShellService systemShellService, Path currentProject) {
      super(systemShellService, () -> currentProject);
    }

    /** {@code startProcess} の条件を再現するための補助情報を返す。 */
    @Override
    Process startProcess(List<String> commandLine, Path directory) throws IOException {
      this.started = true;
      this.commandLine = commandLine;
      this.directory = directory;
      return new CompletedProcess();
    }
  }

  /** 検証条件を再現するための完了済みのプロセス。 */
  private static class CompletedProcess extends Process {
    /** 検証用に、共通の実行基盤で参照する出力逐次データを返す。 */
    @Override
    public java.io.OutputStream getOutputStream() {
      return java.io.OutputStream.nullOutputStream();
    }

    /** 検証用に、共通の実行基盤で参照する入力逐次データを返す。 */
    @Override
    public java.io.InputStream getInputStream() {
      return java.io.InputStream.nullInputStream();
    }

    /** 検証用に、共通の実行基盤で参照するエラー逐次データを返す。 */
    @Override
    public java.io.InputStream getErrorStream() {
      return java.io.InputStream.nullInputStream();
    }

    /** 検証用に、必要な状態が整ってから先へ進むため、対象を待つ。 */
    @Override
    public int waitFor() {
      return 0;
    }

    /** {@code exitValue} の条件を再現するための補助情報を返す。 */
    @Override
    public int exitValue() {
      return 0;
    }

    /** {@code destroy} の条件を再現するため、検証用の操作や状態を扱う。 */
    @Override
    public void destroy() {}
  }
}
