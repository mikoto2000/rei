package dev.mikoto2000.rei;

import org.jline.terminal.Terminal;
import org.jline.terminal.spi.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import dev.mikoto2000.rei.voice.VoiceCommand;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReiApplicationVoiceAutoStartTest {
  @Test void requiresActualInteractiveInputAndAUsableSystemTerminal() {
    var terminal = mock(TerminalExt.class);
    var provider = mock(TerminalProvider.class);
    when(terminal.getType()).thenReturn("xterm-256color");
    when(terminal.getSystemStream()).thenReturn(SystemStream.Output);
    when(terminal.getProvider()).thenReturn(provider);
    assertThat(ReiApplication.isInteractiveTerminal(terminal)).isFalse();
    when(provider.isSystemStream(SystemStream.Input)).thenReturn(true);
    assertThat(ReiApplication.isInteractiveTerminal(terminal)).isTrue();
    when(terminal.getType()).thenReturn(Terminal.TYPE_DUMB);
    assertThat(ReiApplication.isInteractiveTerminal(terminal)).isFalse();
    when(terminal.getType()).thenReturn(Terminal.TYPE_DUMB_COLOR);
    assertThat(ReiApplication.isInteractiveTerminal(terminal)).isFalse();
    when(terminal.getType()).thenReturn("windows-conemu");
    when(terminal.getSystemStream()).thenReturn(null);
    assertThat(ReiApplication.isInteractiveTerminal(terminal)).isFalse();
    assertThat(ReiApplication.isInteractiveTerminal(mock(Terminal.class))).isFalse();
    assertThat(ReiApplication.isInteractiveTerminal(null)).isFalse();
  }

  @Test void terminalProbeFailureCannotAbortShellStartup() {
    var terminal = mock(TerminalExt.class);
    var provider = mock(TerminalProvider.class);
    when(terminal.getType()).thenReturn("xterm");
    when(terminal.getSystemStream()).thenReturn(SystemStream.Output);
    when(terminal.getProvider()).thenReturn(provider);
    when(provider.isSystemStream(SystemStream.Input)).thenThrow(new UnsatisfiedLinkError("unavailable"));
    assertThat(ReiApplication.isInteractiveTerminal(terminal)).isFalse();
  }

  @Test void shellStartupUsesConfiguredVoiceCommandAndPinnedShellWriter() {
    var voice = mock(VoiceCommand.class);
    java.util.function.Consumer<String> output = line -> {};
    var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    root.addSubcommand("voice", voice);
    var terminal = mock(TerminalExt.class);
    var provider = mock(TerminalProvider.class);
    when(terminal.getType()).thenReturn("xterm");
    when(terminal.getSystemStream()).thenReturn(SystemStream.Output);
    when(terminal.getProvider()).thenReturn(provider);
    when(provider.isSystemStream(SystemStream.Input)).thenReturn(true);
    ReiApplication.startVoiceAutomatically(root, terminal, output);
    verify(voice).autoStart(true, output);
    when(provider.isSystemStream(SystemStream.Input)).thenReturn(false);
    ReiApplication.startVoiceAutomatically(root, terminal, output);
    verify(voice).autoStart(false, output);
  }
}
