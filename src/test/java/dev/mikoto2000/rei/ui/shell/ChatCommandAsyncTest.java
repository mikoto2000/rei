package dev.mikoto2000.rei.ui.shell;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCommandAsyncTest {
  @Test void shellCommandReturnsBeforeAgentAndAcceptsGuidance() {
    var tasks = new ArrayList<Runnable>();
    var service = mock(ChatExecutionService.class);
    var command = new ChatCommand(service, mock(ChatResponseNarrator.class));
    command.setInputRouter(new ConversationInputRouter(tasks::add,
        (context, prompt, queue) -> service.execute(context, prompt, queue)));
    var cli = new picocli.CommandLine(command);
    assertThat(cli.execute("start")).isZero();
    assertThat(cli.execute("guidance")).isZero();
    verifyNoInteractions(service);
    assertThat(tasks).hasSize(1);
  }
}
