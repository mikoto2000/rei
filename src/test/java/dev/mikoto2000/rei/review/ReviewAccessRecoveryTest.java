package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.chat.ToolLoopSupport;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolEventCallbackProvider;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

@org.junit.jupiter.api.Tag("integration")
class ReviewAccessRecoveryTest {
  @TempDir Path dir;
  ReviewCoverageLedger ledger;
  ReviewCoverageLedger.Job original;
  Tools files;
  ReviewTools reviews;
  final ReviewCoverageLedger.Scope owner =
      new ReviewCoverageLedger.Scope("project", "chat", "old-run");
  final List<AgentEvent> events = new ArrayList<>();

  @BeforeEach
  void setup() throws Exception {
    try (var migration = new StorageMigrationCoordinator(dir)) {
      migration.prepare();
    }
    ledger = new ReviewCoverageLedger(new StorageDatabase(dir));
    original =
        ledger.create(
            owner,
            "PRIVATE_CRITERIA",
            "v1",
            List.of(
                new ReviewCoverageLedger.Target(
                    "PRIVATE_PATH.md",
                    "PRIVATE_VERSION",
                    1,
                    List.of(new ReviewCoverageLedger.Span(0, 6, 1, 1, 0, 6)))));
    files = mock(Tools.class);
    reviews =
        new ReviewTools(ledger, new RawToolResultStore(dir), files, TokenEstimator.conservative());
  }

  @ParameterizedTest
  @ValueSource(strings = {"project", "conversation", "run"})
  void accessDenialReturnsSafeGuidanceWithoutReadingOrGrantingAccess(String changed)
      throws Exception {
    var current =
        new AgentRunContext(
            changed.equals("run") ? "new-run" : "old-run",
            changed.equals("conversation") ? "other-chat" : "chat",
            dir,
            changed.equals("project") ? "other-project" : "project");
    var callbacks =
        new ToolEventCallbackProvider(
                MethodToolCallbackProvider.builder().toolObjects(reviews).build(),
                new AgentEventFactory(Clock.systemUTC()),
                events::add)
            .getToolCallbacks();
    var callback =
        java.util.Arrays.stream(callbacks)
            .filter(t -> t.getToolDefinition().name().equals("reviewStatus"))
            .findFirst()
            .orElseThrow();
    try (var scope = AgentRunScope.open(current)) {
      String result =
          callback.call(
              StorageDatabase.JSON.writeValueAsString(Map.of("reviewJobId", original.id())));
      var failure = StorageDatabase.JSON.readTree(result);
      assertThat(failure.path("success").asBoolean()).isFalse();
      assertThat(failure.path("error").asText()).isEqualTo("REVIEW_ACCESS_DENIED");
      assertThat(failure.path("message").asText()).contains("Project", "Run");
      assertThat(failure.path("guidance").asText())
          .contains("checkpointShow", "checkpointResume", "beginReview", "明示的", "再試行しない");
      assertThat(result).doesNotContain("PRIVATE", "old-run", "other-project");
      assertThat(dev.mikoto2000.rei.core.stagnation.ProgressEvaluator.failedResult(result))
          .isTrue();
      assertThatThrownBy(() -> reviews.reviewStatus(original.id()))
          .hasMessage("REVIEW_ACCESS_DENIED");
    }
    verifyNoInteractions(files);
    assertThat(ledger.get(original.id(), owner)).isEqualTo(original);
    assertThat(events)
        .extracting(AgentEvent::type)
        .containsExactly(AgentEventType.TOOL_STARTED, AgentEventType.TOOL_FAILED);
    var error = ((ToolFailedPayload) events.getLast().payload()).error();
    assertThat(error.code()).isEqualTo("REVIEW_ACCESS_DENIED");
    assertThat(error.message()).contains("許可されていません").doesNotContain("PRIVATE");
  }

  @Test
  void deniedOldReviewDoesNotPreventNewAuthorizedReviewInSameToolLoop() throws Exception {
    var current = new AgentRunContext("new-run", "chat", dir, "project");
    when(files.reviewSnapshot("current.md"))
        .thenReturn(new Tools.ReviewSnapshot("current.md", "v2", "current source"));
    var callbacks =
        new ToolEventCallbackProvider(
                MethodToolCallbackProvider.builder().toolObjects(reviews).build(),
                new AgentEventFactory(Clock.systemUTC()),
                events::add)
            .getToolCallbacks();
    var prompt =
        new Prompt(
            "review current files",
            OpenAiChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(Map.of("toolCallId", "access-recovery"))
                .build());
    var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "old",
                                    "function",
                                    "reviewStatus",
                                    StorageDatabase.JSON.writeValueAsString(
                                        Map.of("reviewJobId", original.id()))),
                                new AssistantMessage.ToolCall(
                                    "new",
                                    "function",
                                    "beginReview",
                                    "{\"paths\":[\"current.md\"],\"criteria\":\"correctness\",\"criteriaVersion\":\"v1\"}")))
                        .build())));
    try (var scope = AgentRunScope.open(current)) {
      var result = new ToolLoopSupport().execute(prompt, response);
      var feedback = (ToolResponseMessage) result.conversationHistory().getLast();
      assertThat(feedback.getResponses()).hasSize(2);
      assertThat(feedback.getResponses().getFirst().responseData())
          .contains("REVIEW_ACCESS_DENIED");
      var created = StorageDatabase.JSON.readTree(feedback.getResponses().getLast().responseData());
      String newId = created.path("id").asText();
      assertThat(newId).isNotBlank().isNotEqualTo(original.id());
      assertThat(reviews.reviewStatus(newId).targets())
          .extracting(ReviewCoverageLedger.Target::path)
          .containsExactly("current.md");
      assertThatThrownBy(() -> reviews.reviewStatus(original.id()))
          .hasMessage("REVIEW_ACCESS_DENIED");
    }
    assertThat(ledger.get(original.id(), owner)).isEqualTo(original);
    assertThat(events)
        .extracting(AgentEvent::type)
        .containsExactly(
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_FAILED,
            AgentEventType.TOOL_STARTED,
            AgentEventType.TOOL_COMPLETED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"project", "conversation"})
  void rejectedResumeCannotAddParticipant(String changed) {
    var invalid =
        new ReviewCoverageLedger.Scope(
            changed.equals("project") ? "other" : "project",
            changed.equals("conversation") ? "other" : "chat",
            "resumed");
    assertThatThrownBy(() -> ledger.resume(original.id(), owner, invalid))
        .hasMessage("REVIEW_ACCESS_DENIED");
    assertThat(ledger.get(original.id(), owner)).isEqualTo(original);
    assertThatThrownBy(() -> ledger.get(original.id(), invalid)).hasMessage("REVIEW_ACCESS_DENIED");
  }
}
