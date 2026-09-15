package dev.mikoto2000.rei.externalagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import java.util.Map;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalAgentCommandTest {
  @Test void slashDuringActiveChatGetsItsOwnQueuedRun() {
    var tasks = new java.util.ArrayList<Runnable>();
    var requests = new java.util.ArrayList<String>();
    var router = new dev.mikoto2000.rei.core.chat.ConversationInputRouter(tasks::add,
        (owner, text, queue) -> requests.add(text), (owner, entry) -> {});
    router.submit(dev.mikoto2000.rei.core.project.ProjectService.currentProjectOrStartupDirectory(),
        dev.mikoto2000.rei.llm.ConversationIds.currentChat(), "active chat");
    assertEquals(0, new CommandLine(new ExternalAgentCommand(router)).execute("codex", "review"));
    tasks.getFirst().run();
    assertEquals(2, tasks.size());
    tasks.getLast().run();
    assertEquals(java.util.List.of("active chat", "/agent codex review"), requests);
  }
  @Test void slashAdapterSubmitsCanonicalRequestAndRejectsUnsupportedValues() {
    var router = mock(dev.mikoto2000.rei.core.chat.ConversationInputRouter.class);
    var command = new CommandLine(new ExternalAgentCommand(router));
    assertEquals(0, command.execute("codex", "review", "docs/design.md"));
    verify(router).submitNewRun(any(), anyString(), eq("/agent codex review docs/design.md"));
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
