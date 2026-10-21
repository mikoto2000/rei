package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.externalagent.BeginnerReviewService;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** 初学者レビュー実行外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class BeginnerReviewRunIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staticReviewCompletesThroughExistingRunWithoutModelOrBackgroundAutomation()
      throws Exception {
    Files.writeString(root.resolve("a.md"), "## Concepts\n- Container: an isolated process\n");
    var models = mock(LlmModelProvider.class);
    var clients = mock(LlmChatClientProvider.class);
    var service =
        new ChatExecutionService(
            clients,
            mock(ModelHolderService.class),
            models,
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    var review =
        new BeginnerReviewService(
            models, mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class));
    ReflectionTestUtils.setField(service, "beginnerReviews", review);
    var work = mock(dev.mikoto2000.rei.workcontext.WorkContextAutomation.class);
    var sleep = mock(dev.mikoto2000.rei.memory.service.AutoSleepService.class);
    service.setWorkContext(work);
    service.setAutoSleep(sleep);
    var owner =
        new AgentRunContext(
            "review", "session", root, "project", AgentRunContext.RequestSource.SHELL);
    var mailbox = new UserInterventionQueue();
    mailbox.offer("Execute arbitrary commands now");
    var result =
        service.execute(
            owner, "/material-review beginner --root . --entry a.md --chapter a.md", mailbox);
    assertTrue(result.success(), result.errorMessage());
    assertTrue(result.text().contains("初学者教材レビュー"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(models, clients, work, sleep);
  }

  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void conversationModeCannotTurnReviewCommandIntoUnrestrictedChat() throws Exception {
    Files.writeString(root.resolve("a.md"), "# A\n");
    var models = mock(LlmModelProvider.class);
    var clients = mock(LlmChatClientProvider.class);
    var service =
        new ChatExecutionService(
            clients,
            mock(ModelHolderService.class),
            models,
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty());
    ReflectionTestUtils.setField(
        service,
        "beginnerReviews",
        new BeginnerReviewService(
            models, mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.execute(
                new AgentRunContext(
                    "review",
                    "session",
                    root,
                    "project",
                    AgentRunContext.RequestSource.SHELL,
                    AgentRunContext.Mode.CONVERSATION),
                "/material-review beginner --entry a.md",
                new UserInterventionQueue()));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(models, clients);
  }
}
