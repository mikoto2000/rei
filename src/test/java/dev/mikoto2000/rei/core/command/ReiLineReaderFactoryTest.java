package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

import picocli.CommandLine;

class ReiLineReaderFactoryTest {

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
