package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import picocli.CommandLine;

/** 外部エージェントコマンドの結果と状態の契約を検証するテスト。 */
class ExternalAgentCommandTest {
  /** 検証に使う検証専用の一時。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void slashDuringActiveChatGetsItsOwnQueuedRun() {
    var tasks = new java.util.ArrayList<Runnable>();
    var requests = new java.util.ArrayList<String>();
    var router =
        new dev.mikoto2000.rei.core.chat.ConversationInputRouter(
            tasks::add, (owner, text, queue) -> requests.add(text), (owner, entry) -> {});
    var projects =
        new dev.mikoto2000.rei.core.project.ProjectService(
            temp,
            new dev.mikoto2000.rei.core.project.ProjectRegistry(temp.resolve("projects.json")));
    var shell =
        new dev.mikoto2000.rei.application.session.ShellConversationService(
            projects,
            new dev.mikoto2000.rei.application.session.SessionLifecycle(
                new dev.mikoto2000.rei.conversation.FileSessionRepository(
                    temp.resolve("sessions.json")),
                java.time.Clock.systemUTC()),
            router::submit);
    try (var scope = projects.newClient().open()) {
      shell.submit("active chat");
      assertEquals(0, new CommandLine(new ExternalAgentCommand(shell)).execute("codex", "review"));
      tasks.getFirst().run();
      assertEquals(2, tasks.size());
      tasks.getLast().run();
      assertEquals(java.util.List.of("active chat", "/agent codex review"), requests);
    }
  }

  /** 未対応の値一覧を受け付けないことを確認する。 */
  @Test
  void slashAdapterSubmitsCanonicalRequestAndRejectsUnsupportedValues() {
    var router = mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);
    var command = new CommandLine(new ExternalAgentCommand(router));
    assertEquals(0, command.execute("codex", "review", "docs/design.md"));
    verify(router).submit("/agent codex review docs/design.md");
    reset(router);
    for (String[] args : new String[][] {{}, {"codex"}, {"foo", "review"}, {"codex", "implement"}})
      assertNotEquals(0, command.execute(args));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(router);
  }

  /** 意味によるツールについて、共有サービスを利用することを確認する。 */
  @Test
  void semanticToolUsesSharedServiceAndHasNoAgentOrWorkingDirectoryArgument() {
    var service = mock(ExternalAgentDelegationService.class);
    var tools = new ExternalAgentTools(service);
    var callback =
        java.util.Arrays.stream(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                    .toolObjects(tools)
                    .build()
                    .getToolCallbacks())
            .filter(candidate -> candidate.getToolDefinition().name().equals("requestCodexReview"))
            .findFirst()
            .orElseThrow();
    assertEquals("requestCodexReview", callback.getToolDefinition().name());
    assertTrue(callback.getToolDefinition().description().contains("explicitly"));
    String schema = callback.getToolDefinition().inputSchema();
    assertFalse(schema.contains("projectRoot"));
    assertFalse(schema.contains("\"agent\""));
    tools.requestCodexReview("review", null, "decisions", new ToolContext(Map.of()));
    verify(service).review(null, "review", null, "decisions");
  }
}
