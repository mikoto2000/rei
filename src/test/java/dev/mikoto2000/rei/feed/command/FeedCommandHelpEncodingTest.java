package dev.mikoto2000.rei.feed.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** フィードコマンド使い方文字コードの結果と状態の契約を検証するテスト。 */
class FeedCommandHelpEncodingTest {

  /** フィードの購読と収集で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void helpContainsReadableJapaneseDescription() {
    CommandLine commandLine = new CommandLine(new FeedCommand());
    StringWriter writer = new StringWriter();

    commandLine.usage(new PrintWriter(writer));

    assertThat(writer.toString())
        .contains("RSS/Atom フィードを操作します")
        .contains("フィードを追加します")
        .contains("OPML ファイルからフィードを一括登録します")
        .contains("登録済みフィードを一覧します");
  }
}
