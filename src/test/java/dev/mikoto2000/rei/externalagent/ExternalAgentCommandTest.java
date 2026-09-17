package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import java.util.Map;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalAgentCommandTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
  @Test void slashDuringActiveChatGetsItsOwnQueuedRun() {
    var tasks = new java.util.ArrayList<Runnable>();
    var requests = new java.util.ArrayList<String>();
    var router = new dev.mikoto2000.rei.core.chat.ConversationInputRouter(tasks::add,
        (owner, text, queue) -> requests.add(text), (owner, entry) -> {});
    var projects = new dev.mikoto2000.rei.core.project.ProjectService(temp,new dev.mikoto2000.rei.core.project.ProjectRegistry(temp.resolve("projects.json")));
    var shell = new dev.mikoto2000.rei.application.session.ShellConversationService(projects,
        new dev.mikoto2000.rei.application.session.SessionLifecycle(new dev.mikoto2000.rei.conversation.FileSessionRepository(temp.resolve("sessions.json")),java.time.Clock.systemUTC()),router::submit);
    try(var scope=projects.newClient().open()) {
    shell.submit("active chat");
    assertEquals(0, new CommandLine(new ExternalAgentCommand(shell)).execute("codex", "review"));
    tasks.getFirst().run();
    assertEquals(2, tasks.size());
    tasks.getLast().run();
    assertEquals(java.util.List.of("active chat", "/agent codex review"), requests);
    }
  }
  @Test void slashAdapterSubmitsCanonicalRequestAndRejectsUnsupportedValues() {
    var router = mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);
    var command = new CommandLine(new ExternalAgentCommand(router));
    assertEquals(0, command.execute("codex", "review", "docs/design.md"));
    verify(router).submit("/agent codex review docs/design.md");
    reset(router);
    for (String[] args : new String[][]{{}, {"codex"}, {"foo", "review"}, {"codex", "implement"}})
      assertNotEquals(0, command.execute(args));
    verifyNoInteractions(router);
  }
  @Test void semanticToolUsesSharedServiceAndHasNoAgentOrWorkingDirectoryArgument() {
    var service = mock(ExternalAgentDelegationService.class);
    var tools = new ExternalAgentTools(service);
    var callback = org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()[0];
    assertEquals("requestCodexReview", callback.getToolDefinition().name());
    assertTrue(callback.getToolDefinition().description().contains("explicitly"));
    String schema = callback.getToolDefinition().inputSchema();
    assertFalse(schema.contains("projectRoot"));
    assertFalse(schema.contains("\"agent\""));
    tools.requestCodexReview("review", null, "decisions", new ToolContext(Map.of()));
    verify(service).review(null, "review", null, "decisions");
  }
}
