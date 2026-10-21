package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.ui.shell.RootCommand;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 作業ルートコマンド画像の結果と状態の契約を検証するテスト。 */
class RootCommandImageTest {

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void rootCommandRegistersImageCommand() {
    CommandLine commandLine = new CommandLine(new RootCommand());

    assertThat(commandLine.getSubcommands()).containsKey("image");
  }

  /** 共通の実行基盤で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void rootCommandDoesNotRegisterRemovedTuiCommand() {
    CommandLine commandLine = new CommandLine(new RootCommand());

    assertThat(commandLine.getSubcommands()).doesNotContainKey("tui");
    assertThat(commandLine.getUsageMessage()).doesNotContain("tui");
  }
}
