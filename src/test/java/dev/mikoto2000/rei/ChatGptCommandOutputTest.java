package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.chatgpt.*;
import java.io.*;
import java.util.List;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import picocli.CommandLine;

class ChatGptCommandOutputTest {
  @CommandLine.Command(name = "fixture")
  static class Root {}

  @Test
  void sharedCommandSpecRebindingPreservesTerminalWriter() {
    @SuppressWarnings("unchecked")
    var provider = (ObjectProvider<PlanOAuth>) mock(ObjectProvider.class);
    var oauth = mock(PlanOAuth.class);
    when(provider.getIfAvailable()).thenReturn(oauth);
    when(oauth.status()).thenReturn(List.of("日本語の登録状態"));
    var bean = new ChatGptCommand(provider, null, null);
    var command = new CommandLine(new Root()).addSubcommand("chatgpt", bean);
    var output = new StringWriter();
    var terminal = mock(Terminal.class);
    when(terminal.writer()).thenReturn(new PrintWriter(output));
    ReiApplication.configureCommandOutput(command, terminal);
    new CommandLine(bean).setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute("chatgpt", "status"));
    assertTrue(output.toString().contains("日本語の登録状態"));
  }
}
