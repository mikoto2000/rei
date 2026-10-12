package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.storage.*;
import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;

class ReviewDeliveryBoundaryTest {
  @TempDir Path dir;
  @Test void realToolDispatcherAttachesReceiptButOnlyConfirmedPromptMarksDelivery() throws Exception {
    Files.writeString(dir.resolve("lesson.md"), "本文🙂");
    try (var migration = new StorageMigrationCoordinator(dir.resolve("data"))) { migration.prepare(); }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(dir.resolve("data")));
    var reviews = new ReviewTools(ledger, new RawToolResultStore(dir.resolve("data")), new Tools(), TokenEstimator.conservative());
    var owner = new AgentRunContext("run", "chat", dir, "project");
    try (var scope = AgentRunScope.open(owner)) {
      var job = reviews.beginReview(List.of("lesson.md"), "correctness", "v1");
      var callbacks = DeliveryToolRegistration.provider(reviews).getToolCallbacks();
      var options = OpenAiChatOptions.builder().toolCallbacks(callbacks).build();
      String args = StorageDatabase.JSON.writeValueAsString(Map.of("reviewJobId", job.id(), "path", "lesson.md",
          "expectedVersion", job.targets().getFirst().version(), "offset", 0, "tokenBudget", 256));
      var assistant = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall("call", "function", "readReviewFile", args))).build();
      var dispatched = new ToolLoopSupport().execute(new Prompt(List.of(new UserMessage("review")), options), new ChatResponse(List.of(new Generation(assistant))));
      assertThat(ledger.get(job.id(), ReviewTools.scope(owner)).ranges().getFirst().delivered()).isFalse();
      reviews.confirmedPrompt(new Prompt(dispatched.conversationHistory(), options), owner);
      assertThat(ledger.get(job.id(), ReviewTools.scope(owner)).ranges().getFirst().delivered()).isTrue();
    }
  }
}
