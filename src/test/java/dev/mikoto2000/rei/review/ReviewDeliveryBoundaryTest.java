package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** レビュー配信境界条件の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ReviewDeliveryBoundaryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 変更内容のレビューで、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void realToolDispatcherAttachesReceiptButOnlyConfirmedPromptMarksDelivery() throws Exception {
    Files.writeString(dir.resolve("lesson.md"), "本文🙂");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) {
      migration.prepare();
    }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data")));
    var reviews =
        new ReviewTools(
            ledger,
            new RawToolResultStore(dir.resolve("data")),
            new Tools(),
            TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", dir, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = reviews.beginReview(List.of("lesson.md"), "correctness", "v1");
      var callbacks = DeliveryToolRegistration.provider(reviews).getToolCallbacks();
      var options = OpenAiChatOptions.builder().toolCallbacks(callbacks).build();
      String args =
          StorageDatabase.JSON.writeValueAsString(
              Map.of(
                  "reviewJobId",
                  job.id(),
                  "path",
                  "lesson.md",
                  "expectedVersion",
                  job.targets().getFirst().version(),
                  "offset",
                  0,
                  "tokenBudget",
                  256));
      var assistant =
          AssistantMessage.builder()
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall("call", "function", "readReviewFile", args)))
              .build();
      var dispatched =
          new ToolLoopSupport()
              .execute(
                  new Prompt(List.of(new UserMessage("review")), options),
                  new ChatResponse(List.of(new Generation(assistant))));
      assertThat(ledger.get(job.id(), ReviewTools.scope(owner)).ranges().getFirst().delivered())
          .isFalse();
      reviews.confirmedPrompt(new Prompt(dispatched.conversationHistory(), options), owner);
      assertThat(ledger.get(job.id(), ReviewTools.scope(owner)).ranges().getFirst().delivered())
          .isTrue();
    }
  }
}
