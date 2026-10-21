package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** プロジェクトコマンド使い方文字コードの結果と状態の契約を検証するテスト。 */
class ProjectCommandHelpEncodingTest {

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void helpContainsReadableJapaneseDescription() {
    CommandLine commandLine = new CommandLine(new ProjectCommand());
    StringWriter writer = new StringWriter();

    commandLine.usage(new PrintWriter(writer));

    assertThat(writer.toString()).contains("作業ディレクトリ");
  }
}
