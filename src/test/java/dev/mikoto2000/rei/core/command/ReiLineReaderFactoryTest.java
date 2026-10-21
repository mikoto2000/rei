package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.ui.shell.RootCommand;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** れい行読み取り元生成元の結果と状態の契約を検証するテスト。 */
class ReiLineReaderFactoryTest {

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void canonicalCompleterIncludesShellBuiltinsAndRootCommands() {
    CommandLine command = new CommandLine(new RootCommand(), CommandLine.defaultFactory());
    var completer = ReiLineReaderFactory.completer(command);
    var line = new DefaultParser().parse("/he", 3);
    List<Candidate> candidates = new ArrayList<>();

    completer.complete(mock(LineReader.class), line, candidates);

    assertTrue(candidates.stream().anyMatch(candidate -> "/help".equals(candidate.value())));
    assertFalse(candidates.stream().anyMatch(candidate -> "/tui".equals(candidate.value())));
  }
}
