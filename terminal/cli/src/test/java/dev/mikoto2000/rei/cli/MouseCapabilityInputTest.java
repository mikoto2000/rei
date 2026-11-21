package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mikoto2000.rei.core.completion.ApprovalMenuLineReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.Curses;
import org.jline.utils.InfoCmp.Capability;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers both local X10 and CI SGR terminfo, independent of the host's infocmp database. */
@Timeout(15)
class MouseCapabilityInputTest {
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"\\E[M", "\\E[<", "\\033[M", "\\033[<", "\033[M", "\033[<"})
  void mouseCapabilitiesPreserveWindowsPathsAndDecodedMouseBindings(String mouseCapability)
      throws Exception {
    for (String mode : List.of(LineReader.EMACS, LineReader.VIINS)) {
      try (var terminal =
          new DumbTerminal(
              "fixture",
              "xterm",
              new ByteArrayInputStream(new byte[0]),
              new ByteArrayOutputStream(),
              StandardCharsets.UTF_8) {
            @Override
            public String getStringCapability(Capability capability) {
              return capability == Capability.key_mouse
                  ? mouseCapability
                  : super.getStringCapability(capability);
            }
          }) {
        var reader = new ApprovalMenuLineReader(terminal, false);
        reader.setParser(
            new dev.mikoto2000.rei.ui.shell.ShellCompletionParser(
                java.nio.file.Path.of(System.getProperty("user.home"))));
        reader.setKeyMap(mode);
        if (mouseCapability != null) {
          assertEquals(
              new Reference(LineReader.MOUSE),
              reader.getKeys().getBound(Curses.tputs(mouseCapability)));
        }
        for (String path :
            List.of(
                "C:\\Users\\runner\\report.txt",
                "F:\\",
                "\\\\server\\share\\rei",
                "\"F:\\My Documents\\rei\\\"")) {
          reader.runMacro(path + "\r");
          assertEquals(path, reader.readLine(""), mode + ": " + mouseCapability);
        }
      }
    }
  }
}
