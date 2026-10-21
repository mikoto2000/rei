package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.command.StorageCommand;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 保存コマンドの結果と状態の契約を検証するテスト。 */
class StorageCommandTest {
  /** データ保存の共通処理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void rootRegistersStorage() {
    assertThat(new CommandLine(new dev.mikoto2000.rei.ui.shell.RootCommand()).getSubcommands())
        .containsKey("storage");
  }

  /** データ保存の共通処理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void absentProjectDoesNotSilentlyScanGlobalStorage() {
    var error = new StringWriter();
    var command = new CommandLine(new StorageCommand());
    command.setErr(new PrintWriter(error));
    command.setOut(new PrintWriter(new StringWriter()));
    assertThat(command.execute("status", "--project", "current")).isEqualTo(2);
    assertThat(error.toString()).contains("No current project");
  }

  /** データ保存の共通処理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidScopeIsRejected() {
    var command = new CommandLine(new StorageCommand());
    command.setErr(new PrintWriter(new StringWriter()));
    assertThat(command.execute("status", "--project", "all")).isEqualTo(2);
  }
}
