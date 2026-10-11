package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import picocli.CommandLine;

class ChatGptCommandTest {
  @Test
  void disabledConfigurationFailsWithoutOpeningBrowserOrNetwork() {
    var command = new CommandLine(new ChatGptCommand());
    var output = new StringWriter();
    command.setOut(new PrintWriter(output));
    for (String action : List.of("login", "status", "models", "logout"))
      assertEquals(2, command.execute(action));
    assertFalse(output.toString().contains("Bearer"));
  }

  @Test
  void phaseZeroProbeCannotSendWithoutProjectPermission() {
    @SuppressWarnings("unchecked")
    var provider = (ObjectProvider<PlanClient>) mock(ObjectProvider.class);
    var client = mock(PlanClient.class);
    when(provider.getIfAvailable()).thenReturn(client);
    var command = new CommandLine(new ChatGptCommand(null, provider, null));
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(2, command.execute("infer", "--model", "model", "task"));
    verifyNoInteractions(client);
  }
}
