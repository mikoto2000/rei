package dev.mikoto2000.rei.ui.shell;

import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatCommandAsyncTest {
  @org.junit.jupiter.api.io.TempDir Path temp;
  @Test void shellCommandReturnsBeforeAgentAndAcceptsGuidance() {
    var tasks = new ArrayList<Runnable>();
    var service = mock(ChatExecutionService.class);
    var command = new ChatCommand(service, mock(ChatResponseNarrator.class));
    var projects = new dev.mikoto2000.rei.core.project.ProjectService(temp,
        new dev.mikoto2000.rei.core.project.ProjectRegistry(temp.resolve("projects.json")));
    var repository = new dev.mikoto2000.rei.conversation.FileSessionRepository(temp.resolve("sessions.json"));
    var router = new ConversationInputRouter(tasks::add, (context, prompt, queue) -> service.execute(context, prompt, queue));
    command.setShellConversations(new dev.mikoto2000.rei.application.session.ShellConversationService(projects,
        new dev.mikoto2000.rei.application.session.SessionLifecycle(repository, java.time.Clock.systemUTC()), router::submit));
    var cli = new picocli.CommandLine(command);
    try (var scope = projects.newClient().open()) {
    assertThat(cli.execute("start")).isZero();
    assertThat(cli.execute("guidance")).isZero();
    verifyNoInteractions(service);
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run();
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().run();
    var contexts = org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
    verify(service, times(2)).execute(contexts.capture(), anyString(), any());
    assertThat(contexts.getAllValues().get(0).conversationId()).isEqualTo(contexts.getAllValues().get(1).conversationId());
    assertThat(contexts.getAllValues().get(0).runId()).isNotEqualTo(contexts.getAllValues().get(1).runId());
    assertThat(repository.findPage(null, null, 100)).hasSize(1);
    }
  }
}
