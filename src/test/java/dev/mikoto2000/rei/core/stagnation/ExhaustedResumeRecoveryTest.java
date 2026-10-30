package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.contextbudget.ContextAssembler;
import dev.mikoto2000.rei.core.contextbudget.ContextCompressionProperties;
import dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.core.contextbudget.ToolResultCompressor;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

class ExhaustedResumeRecoveryTest {
  @TempDir Path root;
  private static final String PAGE =
      "[{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"startLine\":1,\"content\":[\"known\"],\"error\":null}]";

  private RunExecutionContext context(int count, int replans, int restoration, boolean resumed)
      throws Exception {
    var c =
        new RunExecutionContext(
            "resume",
            new OutputLimitRunBudget(2, 1000),
            new ProgressEvaluator(root),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {});
    c.setRunContext(new AgentRunContext("resume", "conversation", root, "project"));
    c.beginIteration();
    for (String tool : List.of("listFile", "findFile", "readMultiFile")) {
      String result = tool.equals("readMultiFile") ? PAGE : "[\"docs/intro.md\"]";
      c.recordTool(tool, "{}", result, c.evaluator().beforeTool(tool, "{}"));
    }
    c.endIteration();
    var json = new ObjectMapper();
    var saved = (ObjectNode) json.valueToTree(c.progressSnapshot());
    ((ObjectNode) saved.path("budget")).put("stagnationReplans", replans);
    ((ObjectNode) saved.path("continuity").path("detector"))
        .put("stagnationCount", count)
        .put("replanRequested", count >= 4);
    ((ObjectNode) saved.path("continuity").path("evaluator")).put("restorationReads", restoration);
    c.restoreProgress(json.treeToValue(saved, RunExecutionContext.ProgressState.class));
    if (resumed) c.beginContextRestoration();
    return c;
  }

  private ToolCallback tool(String name, java.util.function.Supplier<String> action) {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name(name)
            .description(name)
            .inputSchema("{\"type\":\"object\"}")
            .build();
      }

      public String call(String arguments) {
        return action.get();
      }

      public String call(String arguments, ToolContext context) {
        return call(arguments);
      }
    };
  }

  private void readPage(RunExecutionContext c, int line) {
    String page = PAGE.replace("\"startLine\":1", "\"startLine\":" + line);
    c.beginIteration();
    c.recordTool("readMultiFile", "{}", page, c.evaluator().beforeTool("readMultiFile", "{}"));
    c.endIteration();
  }

  private void fillResumeBatch(RunExecutionContext c) {
    for (int i = 0; i < RunExecutionContext.RESUME_BATCH_READ_LIMIT; i++) readPage(c, i + 2);
    assertThat(c.needsResumeContentWork()).isTrue();
  }

  @Test
  void unitBoundaryKeepsExactEditPreparationAvailable() throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    for (String preparation :
        List.of(
            "readTextChangeSetBase",
            "inspectTextChangeSet",
            "inspectTextDocumentChangeSet",
            "getTextChangeSetDiff",
            "getTextDocumentChangeSetDiff"))
      assertThat(c.resumeAcquisitionBlocked(preparation)).isFalse();
    assertThat(c.resumeAcquisitionBlocked("readMultiFile")).isTrue();
    assertThat(c.hasPendingResumeContentWork()).isTrue();
  }

  private String registeredPage() {
    return "{\"range\":{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"span\":{\"start\":0,\"end\":5}},\"content\":\"known\"}";
  }

  private String reviewedPage() {
    return "{\"id\":\"job\",\"ranges\":[{\"range\":{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"span\":{\"start\":0,\"end\":5}},\"reviewed\":true,\"delivered\":true,\"stale\":false}]}";
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeReviewRejectionCanBeCorrectedWithoutAcceptingItOrRefundingTheRepairBudget(
      boolean corrected) throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    int initialCorrections = OutputLimitRunBudget.DEFAULT_MAX_ARGUMENT_CORRECTIONS - 1;
    var json = new ObjectMapper();
    var saved = (ObjectNode) json.valueToTree(c.progressSnapshot());
    ((ObjectNode) saved.path("budget")).put("argumentCorrections", initialCorrections);
    c.restoreProgress(json.treeToValue(saved, RunExecutionContext.ProgressState.class));
    c.beginContextRestoration();
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    var submissions = new AtomicInteger();
    callbacks.add(tool("readReviewFile", this::registeredPage));
    callbacks.add(
        tool(
            "submitReviewPage",
            () -> {
              int n = submissions.incrementAndGet();
              if (!corrected || n == 1)
                throw new IllegalStateException(
                    "Tool callback wrapper",
                    new dev.mikoto2000.rei.review.ReviewResultRejectedException(
                        "Use the delivered page endpoints"));
              return reviewedPage();
            }));
    var calls = new AtomicInteger();
    Runnable run =
        () ->
            new StagnationChatModel(
                    model(
                        p -> {
                          int n = calls.incrementAndGet();
                          if (n == 1) return call(n, "readReviewFile", "{}");
                          if (n == 2 || !corrected) return call(n, "submitReviewPage", "{}");
                          if (n == 3) {
                            assertThat(p.getInstructions().toString())
                                .contains(
                                    "REVIEW_RESULT_INVALID",
                                    "accepted",
                                    "false",
                                    "Use the delivered page endpoints");
                            assertThat(c.hasPendingResumeContentWork()).isTrue();
                            return call(n, "submitReviewPage", "{}");
                          }
                          return new AssistantMessage("Assessment saved.");
                        }))
                .stream(
                        new Prompt(
                            initial.getInstructions(),
                            options.mutate().toolCallbacks(callbacks).build()))
                    .blockLast();
    if (corrected) {
      run.run();
      assertThat(submissions).hasValue(2);
      assertThat(c.hasPendingResumeContentWork()).isFalse();
      assertThat(c.progressSnapshot().budget().argumentCorrections())
          .isEqualTo(initialCorrections + 1);
    } else {
      assertThatThrownBy(run::run)
          .isInstanceOf(ExecutionStoppedException.class)
          .hasMessageContaining("TOOL_ARGUMENT_REPAIR_EXHAUSTED");
      assertThat(submissions.get())
          .isEqualTo(c.progressSnapshot().budget().argumentCorrections() - initialCorrections + 1);
      assertThat(c.hasPendingResumeContentWork()).isTrue();
    }
    assertThat(c.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void boundaryAllowsOneRegisteredPageForAssessmentAndPersistsTheObligation(boolean legacy)
      throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    assertThat(c.resumeAcquisitionBlocked("readReviewFile")).isFalse();
    c.beginIteration();
    c.recordTool(
        "readReviewFile", "{}", registeredPage(), c.evaluator().beforeTool("readReviewFile", "{}"));
    c.endIteration();
    assertThat(c.resumeAcquisitionBlocked("readReviewFile")).isTrue();
    assertThat(c.needsResumeContentWork()).isTrue();
    var json = new ObjectMapper();
    var serialized = (ObjectNode) json.valueToTree(c.progressSnapshot());
    if (legacy)
      ((ObjectNode) serialized.path("continuity").path("resumeBatch"))
          .remove("reviewPageReadArguments");
    var saved = json.treeToValue(serialized, RunExecutionContext.ProgressState.class);
    var restored = context(0, 2, 8, true);
    restored.restoreProgress(saved);
    restored.beginContextRestoration();
    assertThat(restored.resumeAcquisitionBlocked("readReviewFile")).isTrue();
    assertThat(restored.resumeToolHidden("readReviewFile")).isFalse();
    if (!legacy)
      assertThat(restored.resumeBatchFeedback())
          .contains("Exact review-page read to redeliver", "{}");
    assertThat(restored.resumeAcquisitionBlocked("readReviewFile", "{ } ")).isFalse();
    assertThat(restored.resumeAcquisitionBlocked("readReviewFile", "{\"offset\":5}"))
        .isEqualTo(!legacy);
    restored.beginIteration();
    restored.recordTool(
        "readReviewFile",
        "{}",
        registeredPage(),
        restored.evaluator().beforeTool("readReviewFile", "{}"));
    restored.endIteration();
    assertThat(restored.resumeAcquisitionBlocked("readReviewFile", "{\"offset\":5}")).isTrue();
    assertThat(restored.hasPendingResumeContentWork()).isTrue();
    assertThat(restored.progressSnapshot().continuity().resumeBatch().commitAttempts())
        .isEqualTo(1);
    restored.beginIteration();
    restored.recordTool(
        "submitReviewResult",
        "{}",
        reviewedPage(),
        restored.evaluator().beforeTool("submitReviewResult", "{}"));
    restored.endIteration();
    assertThat(restored.hasPendingResumeContentWork()).isFalse();
    assertThat(restored.resumeAcquisitionBlocked("readReviewFile")).isFalse();
  }

  @Test
  void queuedReviewRedeliveryDoesNotAllowAnotherPagePastTheBoundary() throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    var reads = new AtomicInteger();
    callbacks.add(
        tool(
            "readReviewFile",
            () -> {
              reads.incrementAndGet();
              return registeredPage();
            }));
    callbacks.add(tool("submitReviewResult", this::reviewedPage));
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  int n = calls.incrementAndGet();
                  if (n == 1) return call(n, "readReviewFile", "{}");
                  if (n == 2) {
                    assertThat(names(p)).contains("readReviewFile");
                    return AssistantMessage.builder()
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "new", "function", "readReviewFile", "{\"offset\":5}"),
                                new AssistantMessage.ToolCall(
                                    "redeliver", "function", "readReviewFile", "{ }")))
                        .build();
                  }
                  if (n == 3) {
                    assertThat(reads).hasValue(2);
                    assertThat(p.getInstructions().toString())
                        .contains("Resume work unit requires a consolidated outcome");
                    return call(n, "submitReviewResult", "{}");
                  }
                  return new AssistantMessage("Range assessed.");
                }))
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
    assertThat(reads).hasValue(2);
    assertThat(c.hasPendingResumeContentWork()).isFalse();
  }

  @Test
  void aVerifiedPageCanBeSavedBeforeReadingItsContinuationInTheSameToolResponse() throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    var reads = new AtomicInteger();
    var submitted = new AtomicInteger();
    callbacks.add(
        tool(
            "readReviewFile",
            () ->
                reads.incrementAndGet() == 1
                    ? registeredPage()
                    : registeredPage().replace("\"start\":0,\"end\":5", "\"start\":5,\"end\":10")));
    callbacks.add(
        tool(
            "submitReviewPage",
            () ->
                submitted.incrementAndGet() == 1
                    ? reviewedPage()
                    : reviewedPage().replace("\"start\":0,\"end\":5", "\"start\":5,\"end\":10")));
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  int n = calls.incrementAndGet();
                  if (n == 1) return call(n, "readReviewFile", "{}");
                  if (n == 2) {
                    assertThat(c.resumeAcquisitionBlocked("readReviewFile", "{\"offset\":5}"))
                        .isTrue();
                    return AssistantMessage.builder()
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "assess", "function", "submitReviewPage", "{}"),
                                new AssistantMessage.ToolCall(
                                    "next", "function", "readReviewFile", "{\"offset\":5}")))
                        .build();
                  }
                  if (n == 3) {
                    assertThat(reads).hasValue(2);
                    return call(n, "submitReviewPage", "{}");
                  }
                  return new AssistantMessage("Unit assessed.");
                }))
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
    assertThat(reads).hasValue(2);
    assertThat(submitted).hasValue(2);
    assertThat(c.hasPendingResumeContentWork()).isFalse();
    assertThat(c.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void onlyASuccessfulReviewVersionRebuildReleasesTheOldPage(boolean rebuilt) throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    c.beginIteration();
    c.recordTool(
        "readReviewFile", "{}", registeredPage(), c.evaluator().beforeTool("readReviewFile", "{}"));
    c.endIteration();
    c.beginIteration();
    c.recordTool(
        "restartReview",
        "{}",
        rebuilt ? "{\"id\":\"job\",\"status\":\"REVIEW_PARTIAL\"}" : "{\"error\":\"unavailable\"}",
        c.evaluator().beforeTool("restartReview", "{}"));
    c.endIteration();
    assertThat(c.resumeAcquisitionBlocked("readReviewFile")).isNotEqualTo(rebuilt);
    assertThat(c.hasPendingResumeContentWork()).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void partialResumedReviewContinuesThroughTheLedgerWithoutRefundingBudgets(
      boolean makesProgress, boolean emptyReply) throws Exception {
    var c = context(0, 2, 8, true);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    var submitted = new java.util.concurrent.atomic.AtomicBoolean();
    callbacks.add(tool("beginReview", () -> "{\"id\":\"job\"}"));
    callbacks.add(tool("readReviewFile", this::registeredPage));
    callbacks.add(
        tool(
            "submitReviewResult",
            () -> {
              submitted.set(true);
              return reviewedPage();
            }));
    var assembler = org.mockito.Mockito.mock(ContextAssembler.class);
    org.mockito.Mockito.when(
            assembler.assemble(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    org.mockito.Mockito.when(assembler.reviewContinuation(org.mockito.ArgumentMatchers.any()))
        .thenReturn("job: pending");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              if (!submitted.get())
                throw new ExecutionStoppedException(
                    ExecutionStoppedException.Reason.REVIEW_PARTIAL);
              return null;
            })
        .when(assembler)
        .requireReviewed(org.mockito.ArgumentMatchers.any());
    var invocations = new AtomicInteger();
    Runnable run =
        () ->
            new StagnationChatModel(
                    model(
                        p -> {
                          int n = invocations.incrementAndGet();
                          String messages = p.getInstructions().toString();
                          assertThat(messages)
                              .contains("If no reviewJobId exists", "beginReview", "job: pending");
                          if (!makesProgress || n == 1)
                            return emptyReply ? null : new AssistantMessage("Review complete.");
                          if (n == 2) {
                            assertThat(messages)
                                .contains("persisted review ledger still has unfinished");
                            return call(n, "readReviewFile", "{}");
                          }
                          if (n == 3) return call(n, "submitReviewResult", "{}");
                          return new AssistantMessage("All registered ranges assessed.");
                        }),
                    assembler)
                .stream(
                        new Prompt(
                            initial.getInstructions(),
                            options.mutate().toolCallbacks(callbacks).build()))
                    .blockLast();
    if (makesProgress) run.run();
    else
      assertThatThrownBy(run::run)
          .isInstanceOf(ExecutionStoppedException.class)
          .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(invocations).hasValue(4);
    assertThat(submitted.get()).isEqualTo(makesProgress);
    assertThat(c.progressSnapshot().budget().stagnationReplans()).isEqualTo(2);
  }

  @Test
  void startingAnotherPlanStepDoesNotConfirmPendingWork() throws Exception {
    var plan = new dev.mikoto2000.rei.core.actionplan.ActionPlan();
    plan.addStep("Review the unit");
    var c =
        new RunExecutionContext(
            "resume",
            new OutputLimitRunBudget(2, 1000),
            new ProgressEvaluator(root, plan),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {});
    c.setRunContext(new AgentRunContext("resume", "conversation", root, "project"));
    c.restoreProgress(context(0, 2, 8, false).progressSnapshot());
    c.beginContextRestoration();
    fillResumeBatch(c);

    c.beginIteration();
    var before = c.evaluator().beforeTool("startPlanStep", "{}");
    plan.startStep("step-1");
    c.recordTool("startPlanStep", "{}", "{}", before);
    c.endIteration();
    assertThat(c.diagnosis().lastProgress().kind()).isEqualTo(ProgressEvent.STEP_CHANGED);
    assertThat(c.needsResumeContentWork()).isTrue();

    c.beginIteration();
    before = c.evaluator().beforeTool("completePlanStep", "{}");
    plan.completeStep("step-1");
    c.recordTool("completePlanStep", "{}", "{}", before);
    c.endIteration();
    assertThat(c.hasPendingResumeContentWork()).isFalse();
  }

  @Test
  void unchangedArtifactCannotConfirmTheUnit() throws Exception {
    Files.writeString(
        root.resolve("review.md"), "Known coverage recorded; review remains incomplete");
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(
                            p -> {
                              assertThat(c.hasPendingResumeContentWork()).isTrue();
                              assertThat(names(p)).doesNotContain("readMultiFile");
                              return call(
                                  calls.incrementAndGet(),
                                  "writeMultiFile",
                                  "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
                            }))
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(calls).hasValue(4);
    assertThat(c.hasPendingResumeContentWork()).isTrue();
  }

  @Test
  void openUnitCannotBypassTheSharedCallBudget() throws Exception {
    var c = context(0, 2, 8, true);
    var json = new ObjectMapper();
    var saved = (ObjectNode) json.valueToTree(c.progressSnapshot());
    ((ObjectNode) saved.path("budget")).put("llmCalls", 1000);
    c.restoreProgress(json.treeToValue(saved, RunExecutionContext.ProgressState.class));
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(p -> call(calls.incrementAndGet(), "readMultiFile", "{}")))
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
    assertThat(calls).hasValue(1);
    assertThat(c.hasPendingResumeContentWork()).isTrue();
    assertThat(c.budgetSnapshot().llmCalls()).isEqualTo(1000);
  }

  @Test
  void pendingWorkUnitAndBudgetSurviveCheckpointResume() throws Exception {
    var c = context(0, 2, 8, true);
    for (int i = 0; i < 5; i++) {
      c.consumeNextLlmCall();
      readPage(c, i + 2);
    }
    var json = new ObjectMapper();
    var saved =
        json.readValue(
                json.writeValueAsString(c.progressSnapshot()),
                RunExecutionContext.ProgressState.class)
            .forExplicitResume();
    var resumed = context(0, 2, 8, false);
    resumed.restoreProgress(saved);
    resumed.beginContextRestoration();
    assertThat(resumed.hasPendingResumeContentWork()).isTrue();
    assertThat(resumed.needsResumeContentWork()).isFalse();
    assertThat(resumed.budgetSnapshot()).isEqualTo(c.budgetSnapshot());
    for (int i = 0; i < 3; i++) readPage(resumed, i + 7);
    assertThat(resumed.needsResumeContentWork()).isTrue();
    assertThat(resumed.progressSnapshot().continuity().resumeBatch().contentReads()).isEqualTo(8);

    var sealed =
        json.readValue(
                json.writeValueAsString(resumed.progressSnapshot()),
                RunExecutionContext.ProgressState.class)
            .forExplicitResume();
    resumed.restoreProgress(sealed);
    resumed.beginContextRestoration();
    assertThat(resumed.needsResumeContentWork()).isTrue();
    assertThat(resumed.budgetSnapshot().llmCalls()).isEqualTo(5);
    assertThat(resumed.budgetSnapshot().stagnationReplans()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void earlyCommitRequestSurvivesResumeAndOldCheckpointsStillLoad(boolean attempted)
      throws Exception {
    var c = context(0, 2, 8, true);
    readPage(c, 2);
    c.requireResumeBatchCommit();
    if (attempted) {
      c.beginIteration();
      c.endIteration();
    }
    var json = new ObjectMapper();
    var saved =
        json.readValue(
                json.writeValueAsString(c.progressSnapshot()),
                RunExecutionContext.ProgressState.class)
            .forExplicitResume();
    c.restoreProgress(saved);
    c.beginContextRestoration();
    assertThat(c.needsResumeContentWork()).isTrue();
    assertThat(c.progressSnapshot().continuity().resumeBatch().commitAttempted())
        .isEqualTo(attempted);

    var old = (ObjectNode) json.valueToTree(saved);
    ((ObjectNode) old.path("continuity")).remove("resumeBatch");
    c.restoreProgress(json.treeToValue(old, RunExecutionContext.ProgressState.class));
    c.beginContextRestoration();
    assertThat(c.hasPendingResumeContentWork()).isFalse();
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThatThrownBy(() -> new RunExecutionContext.ResumeBatch(9, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RunExecutionContext.ResumeBatch(0, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void consecutiveWorkUnitsReachTheLastPageWithOneOutcomePerUnit(boolean previouslyFetched)
      throws Exception {
    var c = context(0, 2, 8, true);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    var reads = new AtomicInteger();
    var commits = new AtomicInteger();
    int totalPages = 24;
    if (previouslyFetched) {
      for (int i = 0; i < totalPages; i++) {
        String historical = PAGE.replace("\"startLine\":1", "\"startLine\":" + (i + 2));
        c.evaluator()
            .afterTool(
                "readMultiFile", "{}", historical, c.evaluator().beforeTool("readMultiFile", "{}"));
      }
    }
    callbacks.removeIf(
        t -> Set.of("readMultiFile", "writeMultiFile").contains(t.getToolDefinition().name()));
    callbacks.add(
        tool(
            "readMultiFile",
            () ->
                PAGE.replace("\"startLine\":1", "\"startLine\":" + (reads.incrementAndGet() + 1))));
    callbacks.add(
        tool(
            "writeMultiFile",
            () -> {
              try {
                Files.writeString(
                    root.resolve("review.md"),
                    "Consolidated assessment: source lines 2-"
                        + (reads.get() + 1)
                        + "; remaining pages="
                        + (totalPages - reads.get()));
                commits.incrementAndGet();
                return "[{\"path\":\"review.md\",\"success\":true}]";
              } catch (Exception error) {
                throw new IllegalStateException(error);
              }
            }));
    var invocations = new AtomicInteger();
    var delegate =
        model(
            p -> {
              int n = invocations.incrementAndGet();
              assertThat(n).isLessThanOrEqualTo(28);
              if (commits.get() == 3) {
                assertThat(reads).hasValue(totalPages);
                assertThat(c.hasPendingResumeContentWork()).isFalse();
                return new AssistantMessage("All 24 pages assessed; consolidated result saved.");
              }
              if (names(p).contains("readMultiFile")) {
                assertThat(reads.get()).isLessThan((commits.get() + 1) * 8);
                return call(n, "readMultiFile", "{}");
              }
              assertThat(reads.get()).isEqualTo((commits.get() + 1) * 8);
              assertThat(p.getContents()).contains("consolidated partial assessment");
              return call(
                  n,
                  "writeMultiFile",
                  "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
            });
    new StagnationChatModel(delegate)
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
    assertThat(commits).hasValue(3);
    assertThat(reads).hasValue(totalPages);
    assertThat(Files.readString(root.resolve("review.md")))
        .contains("source lines 2-25", "remaining pages=0");
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aQueuedReadBeyondTheUnitLimitIsNotExecutedButTheOutcomeCanBeSaved(boolean previouslyFetched)
      throws Exception {
    var c = context(0, 2, 8, true);
    if (previouslyFetched) {
      for (int i = 0; i < 8; i++) {
        String historical = PAGE.replace("\"startLine\":1", "\"startLine\":" + (i + 2));
        c.evaluator()
            .afterTool(
                "readMultiFile", "{}", historical, c.evaluator().beforeTool("readMultiFile", "{}"));
      }
    }
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    var reads = new AtomicInteger();
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("readMultiFile"));
    callbacks.add(
        tool(
            "readMultiFile",
            () ->
                PAGE.replace("\"startLine\":1", "\"startLine\":" + (reads.incrementAndGet() + 1))));
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  int n = calls.incrementAndGet();
                  if (n == 1) {
                    var queued = new ArrayList<AssistantMessage.ToolCall>();
                    for (int i = 0; i < 9; i++)
                      queued.add(
                          new AssistantMessage.ToolCall(
                              "read-" + i, "function", "readMultiFile", "{}"));
                    return AssistantMessage.builder().toolCalls(queued).build();
                  }
                  if (n == 2) {
                    assertThat(reads).hasValue(8);
                    assertThat(names(p)).doesNotContain("readMultiFile");
                    var response =
                        p.getInstructions().stream()
                            .filter(
                                org.springframework.ai.chat.messages.ToolResponseMessage.class
                                    ::isInstance)
                            .map(
                                org.springframework.ai.chat.messages.ToolResponseMessage.class
                                    ::cast)
                            .findFirst()
                            .orElseThrow();
                    assertThat(response.getResponses().getLast().responseData())
                        .contains("Resume work unit requires a consolidated outcome");
                    return call(
                        n,
                        "writeMultiFile",
                        "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
                  }
                  assertThat(names(p)).contains("readMultiFile");
                  return new AssistantMessage("Unit result saved.");
                }))
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
    assertThat(reads).hasValue(8);
    assertThat(root.resolve("review.md")).exists();
    assertThat(c.hasPendingResumeContentWork()).isFalse();
  }

  private Prompt prompt(
      RunExecutionContext c, boolean inventory, String owner, AtomicInteger broadCalls) {
    var tools = new ArrayList<ToolCallback>();
    for (String name :
        List.of("listFile", "findFile", "countFileLines", "checkpointShow", "checkpointInspect"))
      tools.add(
          tool(
              name,
              () -> {
                broadCalls.incrementAndGet();
                return "[\"docs/intro.md\"]";
              }));
    tools.add(tool("readMultiFile", () -> PAGE));
    tools.add(
        tool(
            "writeMultiFile",
            () -> {
              try {
                Files.writeString(
                    root.resolve("review.md"),
                    "Known coverage recorded; review remains incomplete");
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
              return "[{\"path\":\"review.md\",\"success\":true}]";
            }));
    var options =
        ToolCallingChatOptions.builder()
            .toolCallbacks(tools)
            .toolContext(Map.of(RunExecutionContext.KEY, c))
            .build();
    var handoff =
        SystemMessage.builder()
            .text(
                "Known inventory: docs/intro.md. Historical evidence; not proof of completed review.")
            .metadata(
                Map.of(
                    "rei.resumeContext",
                    true,
                    "rei.knownFileInventory",
                    inventory,
                    "rei.resumeRunId",
                    owner))
            .build();
    return new Prompt(List.of(handoff, new UserMessage("Continue unfinished work")), options);
  }

  private Set<String> names(Prompt p) {
    return ((ToolCallingChatOptions) p.getOptions())
        .getToolCallbacks().stream()
            .map(t -> t.getToolDefinition().name())
            .collect(java.util.stream.Collectors.toSet());
  }

  private AssistantMessage call(int id, String name, String args) {
    return AssistantMessage.builder()
        .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + id, "function", name, args)))
        .build();
  }

  private ChatModel model(java.util.function.Function<Prompt, AssistantMessage> answer) {
    return new ChatModel() {
      public ChatResponse call(Prompt p) {
        throw new UnsupportedOperationException();
      }

      public Flux<ChatResponse> stream(Prompt p) {
        var reply = answer.apply(p);
        return Flux.just(
            new ChatResponse(reply == null ? List.of() : List.of(new Generation(reply))));
      }
    };
  }

  private Prompt advised(Prompt prompt) {
    var turns =
        org.mockito.Mockito.mock(dev.mikoto2000.rei.conversation.ConversationTurnStore.class);
    org.mockito.Mockito.when(turns.cancelledContext("conversation"))
        .thenReturn("Historical cancelled request; explicit resumption allowed");
    var request =
        org.springframework.ai.chat.client.ChatClientRequest.builder().prompt(prompt).build();
    request =
        new dev.mikoto2000.rei.core.chat.ConversationLifecycleAdvisor(turns, "conversation")
            .before(request, null);
    return new dev.mikoto2000.rei.externalagent.ExternalAgentReviewAdvisor(
            org.mockito.Mockito.mock(
                dev.mikoto2000.rei.externalagent.ExternalAgentDelegationService.class))
        .before(request, null)
        .prompt();
  }

  @Test
  void openAiOutcomeBoundaryRequiresARealToolCallAndRestoresAutoAfterSaving() throws Exception {
    var c = context(0, 2, 8, true);
    var original = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) original.getOptions();
    var openAi =
        org.springframework.ai.openai.OpenAiChatOptions.builder()
            .toolCallbacks(options.getToolCallbacks())
            .toolContext(options.getToolContext())
            .model("probe")
            .toolChoice("auto")
            .build();
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  int n = calls.incrementAndGet();
                  String choice =
                      ((org.springframework.ai.openai.OpenAiChatOptions) p.getOptions())
                          .getToolChoice()
                          .toString();
                  if (n == 1) {
                    assertThat(choice).isEqualTo("auto");
                    return call(n, "readMultiFile", "{}");
                  }
                  if (n == 2) {
                    assertThat(choice).isEqualTo("auto");
                    return new AssistantMessage("I will save the consolidated assessment now.");
                  }
                  if (n == 3) {
                    assertThat(choice).isEqualTo("required");
                    assertThat(names(p)).doesNotContain("readMultiFile").contains("writeMultiFile");
                    return call(
                        n,
                        "writeMultiFile",
                        "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
                  }
                  assertThat(choice).isEqualTo("auto");
                  assertThat(c.hasPendingResumeContentWork()).isFalse();
                  return new AssistantMessage("Assessment saved.");
                }))
        .stream(new Prompt(original.getInstructions(), openAi)).blockLast();
    assertThat(calls).hasValue(4);
    assertThat(root.resolve("review.md")).exists();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void providerTextAtTheBoundaryGetsBoundedCommitAttemptsWithoutRefundingReads(int textReplies)
      throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    var json = new ObjectMapper();
    var state = (ObjectNode) json.valueToTree(c.progressSnapshot());
    ((ObjectNode) state.path("continuity").path("detector")).put("stagnationCount", 3);
    c.restoreProgress(json.treeToValue(state, RunExecutionContext.ProgressState.class));
    c.beginContextRestoration();
    var saved =
        new java.util.concurrent.atomic.AtomicReference<RunExecutionContext.ProgressState>();
    c.setToolResultsCheckpoint(messages -> saved.set(c.progressSnapshot()));
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var generic = (ToolCallingChatOptions) initial.getOptions();
    var options =
        org.springframework.ai.openai.OpenAiChatOptions.builder()
            .toolCallbacks(generic.getToolCallbacks())
            .toolContext(generic.getToolContext())
            .toolChoice("auto")
            .build();
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  int n = calls.incrementAndGet();
                  if (n <= textReplies + 1) {
                    assertThat(saved.get().continuity().resumeBatch().commitAttempts())
                        .isEqualTo(n);
                    assertThat(saved.get().continuity().detector().stagnationCount())
                        .isEqualTo(3 + n - 1);
                    assertThat(
                            ((org.springframework.ai.openai.OpenAiChatOptions) p.getOptions())
                                .getToolChoice())
                        .isEqualTo("required");
                  }
                  if (n <= textReplies) return new AssistantMessage("I will save the assessment.");
                  if (n == textReplies + 1)
                    return call(
                        n,
                        "writeMultiFile",
                        "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
                  assertThat(c.hasPendingResumeContentWork()).isFalse();
                  return new AssistantMessage("Assessment saved.");
                }))
        .stream(new Prompt(initial.getInstructions(), options)).blockLast();
    assertThat(calls).hasValue(textReplies + 2);
    assertThat(root.resolve("review.md")).exists();
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @Test
  void spentCommitAttemptsAreCheckpointedBeforeTheCallAndCannotBeRestoredByResume()
      throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    var saved =
        new java.util.concurrent.atomic.AtomicReference<RunExecutionContext.ProgressState>();
    c.setToolResultsCheckpoint(messages -> saved.set(c.progressSnapshot()));
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(
                            p -> {
                              calls.incrementAndGet();
                              return new AssistantMessage("I will save it.");
                            }))
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(calls).hasValue(4);
    assertThat(saved.get().continuity().resumeBatch().commitAttempts()).isEqualTo(4);
    var json = new ObjectMapper();
    var restored =
        json.readValue(
                json.writeValueAsString(saved.get()), RunExecutionContext.ProgressState.class)
            .forExplicitResume();
    var next = context(0, 2, 8, false);
    next.restoreProgress(restored);
    next.beginContextRestoration();
    var retried = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(
                            p -> {
                              retried.incrementAndGet();
                              return new AssistantMessage("No new call should be sent");
                            }))
                    .stream(prompt(next, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(retried).hasValue(0);
    assertThat(next.budgetSnapshot()).isEqualTo(restored.budget());
  }

  @Test
  @org.junit.jupiter.api.Timeout(20)
  void outcomeToolChoiceIsPresentOnTheActualOpenAiHttpRequest() throws Exception {
    var c = context(0, 2, 8, true);
    var original = prompt(c, true, "resume", new AtomicInteger());
    var tools = (ToolCallingChatOptions) original.getOptions();
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var json = new ObjectMapper();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          var request = json.readTree(exchange.getRequestBody().readAllBytes());
          requests.add(request.path("tool_choice").asText("absent"));
          int turn = requests.size();
          Map<String, Object> delta;
          String finish;
          if (turn == 1 || turn == 3) {
            String name = turn == 1 ? "readMultiFile" : "writeMultiFile";
            String args =
                turn == 1
                    ? "{}"
                    : "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}";
            delta =
                Map.of(
                    "role",
                    "assistant",
                    "tool_calls",
                    List.of(
                        Map.of(
                            "index",
                            0,
                            "id",
                            "wire-" + turn,
                            "type",
                            "function",
                            "function",
                            Map.of("name", name, "arguments", args))));
            finish = "tool_calls";
          } else {
            delta =
                Map.of(
                    "role",
                    "assistant",
                    "content",
                    turn == 2 ? "I will save the assessment now." : "Assessment saved.");
            finish = "stop";
          }
          String chunk =
              json.writeValueAsString(
                  Map.of(
                      "id",
                      "wire",
                      "object",
                      "chat.completion.chunk",
                      "created",
                      0,
                      "model",
                      "probe",
                      "choices",
                      List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish))));
          byte[] bytes =
              ("data: " + chunk + "\n\ndata: [DONE]\n\n")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, bytes.length);
          try (var body = exchange.getResponseBody()) {
            body.write(bytes);
          }
        });
    server.start();
    try {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      var defaults =
          org.springframework.ai.openai.OpenAiChatOptions.builder()
              .baseUrl(url)
              .apiKey("test-key")
              .model("probe")
              .maxRetries(0)
              .build();
      var model = org.springframework.ai.openai.OpenAiChatModel.builder().options(defaults).build();
      var options =
          defaults
              .mutate()
              .toolCallbacks(tools.getToolCallbacks())
              .toolContext(tools.getToolContext())
              .toolChoice("auto")
              .build();
      new StagnationChatModel(model)
          .stream(new Prompt(original.getInstructions(), options)).blockLast();
      assertThat(requests).containsExactly("auto", "auto", "required", "auto");
      assertThat(root.resolve("review.md")).exists();
      assertThat(c.hasPendingResumeContentWork()).isFalse();
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 7})
  void exhaustedResumeUsesHandedOffInventoryAndRestoresDiscoveryAfterVerifiedWork(int count)
      throws Exception {
    var c = context(count, 2, 8, true);
    var broad = new AtomicInteger();
    var calls = new AtomicInteger();
    var estimator = TokenEstimator.conservative();
    var assembler =
        new ContextAssembler(
            new ContextCompressionProperties(),
            estimator,
            new ConversationSummaryRepository(root.resolve("context")),
            new ToolResultCompressor(
                new RawToolResultStore(root.resolve("context")), estimator, 8000, 2000),
            (previous, messages, budget, user) -> "historical reference",
            null,
            null);
    var delegate =
        model(
            p -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                // Reproduce the previous model choice if broad discovery is still advertised.
                if (names(p).contains("listFile")) return call(n, "listFile", "{}");
                assertThat(names(p))
                    .doesNotContain(
                        "listFile",
                        "findFile",
                        "countFileLines",
                        "checkpointShow",
                        "checkpointInspect")
                    .contains("readMultiFile", "writeMultiFile");
                assertThat(p.getContents()).contains("Known inventory");
                return call(
                    n,
                    "writeMultiFile",
                    "{\"files\":[{\"path\":\"review.md\",\"content\":\"Known coverage recorded; review remains incomplete\"}]}");
              }
              assertThat(c.detector().stagnationCount()).isZero();
              assertThat(names(p))
                  .contains(
                      "listFile",
                      "findFile",
                      "countFileLines",
                      "checkpointShow",
                      "checkpointInspect");
              return new AssistantMessage("Intermediate coverage recorded; not a completed review");
            });
    new StagnationChatModel(delegate, assembler)
        .stream(advised(prompt(c, true, "resume", broad))).blockLast();
    assertThat(root.resolve("review.md")).exists();
    assertThat(broad).hasValue(0);
    assertThat(calls).hasValue(2);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ordinary",
        "workCompleted",
        "replanRemaining",
        "restorationRemaining",
        "noInventory",
        "wrongOwner",
        "readOnly",
        "conversation"
      })
  void ordinaryOrUnverifiedRecoveryKeepsDiscoveryTools(String condition) throws Exception {
    var c =
        context(
            condition.equals("workCompleted") ? 0 : 7,
            condition.equals("replanRemaining") ? 1 : 2,
            condition.equals("restorationRemaining") ? 7 : 8,
            !condition.equals("ordinary"));
    if (condition.equals("workCompleted")) c.completeSubgoal("verified");
    if (condition.equals("readOnly") || condition.equals("conversation"))
      c.setRunContext(
          new AgentRunContext(
              "resume",
              "conversation",
              root,
              "project",
              AgentRunContext.RequestSource.SHELL,
              condition.equals("readOnly")
                  ? AgentRunContext.Mode.READ_ONLY
                  : AgentRunContext.Mode.CONVERSATION,
              false));
    var delegate =
        model(
            p -> {
              assertThat(names(p))
                  .contains(
                      "listFile",
                      "findFile",
                      "countFileLines",
                      "checkpointShow",
                      "checkpointInspect");
              throw new java.util.concurrent.CancellationException();
            });
    assertThatThrownBy(
            () ->
                new StagnationChatModel(delegate)
                    .stream(
                            prompt(
                                c,
                                !condition.equals("noInventory"),
                                condition.equals("wrongOwner") ? "different" : "resume",
                                new AtomicInteger()))
                        .blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
  }

  @Test
  void newCoverageKeepsRecoveryFocusedUntilActualWorkAdvances() throws Exception {
    var c = context(0, 2, 8, true);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("readMultiFile"));
    callbacks.add(tool("readMultiFile", () -> PAGE.replace("docs/intro.md", "docs/unseen.md")));
    var calls = new AtomicInteger();
    var delegate =
        model(
            p -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                assertThat(names(p))
                    .doesNotContain("listFile", "countFileLines")
                    .contains("readMultiFile");
                return call(n, "readMultiFile", "{}");
              }
              if (n == 2) {
                assertThat(c.detector().stagnationCount()).isZero();
                assertThat(c.needsResumeWorkRecovery()).isTrue();
                assertThat(names(p)).doesNotContain("listFile", "countFileLines");
                assertThat(p.getContents()).contains("partial assessment", "Do not invent");
                return call(
                    n,
                    "writeMultiFile",
                    "{\"files\":[{\"path\":\"review.md\",\"content\":\"Partial verified coverage; assessment remains incomplete\"}]}");
              }
              assertThat(c.needsResumeWorkRecovery()).isFalse();
              assertThat(names(p)).contains("listFile", "countFileLines");
              return new AssistantMessage(
                  "Intermediate evidence recorded; review remains incomplete");
            });
    new StagnationChatModel(delegate)
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
    assertThat(root.resolve("review.md")).exists();
    assertThat(calls).hasValue(3);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @Test
  void aTextOnlyIntentAfterContentDoesNotCompleteTheResumedWork() throws Exception {
    var c = context(0, 2, 8, true);
    var calls = new AtomicInteger();
    var delegate =
        model(
            p -> {
              int n = calls.incrementAndGet();
              if (n == 1) return call(n, "readMultiFile", "{}");
              if (n == 2)
                return new AssistantMessage("I will continue reading the unfinished page.");
              if (n == 3) {
                assertThat(names(p)).doesNotContain("readMultiFile").contains("writeMultiFile");
                assertThat(p.getContents())
                    .contains("No verified work followed the delivered content");
                return call(
                    n,
                    "writeMultiFile",
                    "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
              }
              assertThat(p.getContents())
                  .doesNotContain("No verified work followed the delivered content");
              assertThat(names(p)).contains("readMultiFile", "listFile");
              return new AssistantMessage("Partial assessment saved with unresolved gaps.");
            });
    new StagnationChatModel(delegate)
        .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast();
    assertThat(calls).hasValue(4);
    assertThat(root.resolve("review.md")).exists();
    assertThat(c.needsResumeContentWork()).isFalse();
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(strings = {"I will continue reading.", "All done.", ""})
  void repeatedTextWithoutWorkStopsWithinTheExistingBudget(String text) throws Exception {
    var c = context(0, 2, 8, true);
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(
                            p -> {
                              int n = calls.incrementAndGet();
                              assertThat(n).isLessThanOrEqualTo(6);
                              return n == 1
                                  ? call(n, "readMultiFile", "{}")
                                  : new AssistantMessage(text);
                            }))
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(calls).hasValue(6);
    assertThat(c.detector().stagnationCount()).isEqualTo(6);
    assertThat(root.resolve("review.md")).doesNotExist();
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"rei.progressFeedback", "rei.resumeWorkReminder"})
  void savedTemporaryHostFeedbackIsReplacedByCurrentObservation(String key) throws Exception {
    var c = context(0, 2, 8, true);
    var original = prompt(c, true, "resume", new AtomicInteger());
    var messages = new ArrayList<>(original.getInstructions());
    messages.add(
        SystemMessage.builder()
            .text("Stale saved host restriction")
            .metadata(Map.of(key, true))
            .build());
    new StagnationChatModel(
            model(
                p -> {
                  assertThat(p.getContents()).doesNotContain("Stale saved host restriction");
                  return new AssistantMessage("Current state observed");
                }))
        .stream(new Prompt(messages, original.getOptions())).blockLast();
  }

  @Test
  void emptyModelStreamsCannotFinishUnassessedContent() throws Exception {
    var c = context(0, 2, 8, true);
    var calls = new AtomicInteger();
    var delegate =
        new ChatModel() {
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          public Flux<ChatResponse> stream(Prompt p) {
            int n = calls.incrementAndGet();
            assertThat(n).isLessThanOrEqualTo(6);
            return n == 1
                ? Flux.just(
                    new ChatResponse(
                        List.of(
                            new Generation(
                                ExhaustedResumeRecoveryTest.this.call(n, "readMultiFile", "{}")))))
                : Flux.empty();
          }
        };
    assertThatThrownBy(
            () ->
                new StagnationChatModel(delegate)
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(calls).hasValue(6);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource({
    "false, inventory",
    "true, inventory",
    "false, missing",
    "true, missing",
    "false, absent",
    "true, absent",
    "false, foreign",
    "true, foreign"
  })
  void bodyPagesAreConsolidatedIntoWorkUnits(boolean newCoverage, String handoff) throws Exception {
    var c = context(0, 2, 8, true);
    String seed = PAGE.replace("[\"known\"]", "[\"one\",\"two\",\"three\"]");
    c.evaluator()
        .afterTool("readMultiFile", "{}", seed, c.evaluator().beforeTool("readMultiFile", "{}"));
    var initial =
        prompt(
            c,
            !handoff.equals("missing"),
            handoff.equals("foreign") ? "other-run" : "resume",
            new AtomicInteger());
    if (handoff.equals("absent"))
      initial =
          new Prompt(
              initial.getInstructions().stream()
                  .filter(m -> !(m instanceof SystemMessage))
                  .toList(),
              initial.getOptions());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("readMultiFile"));
    var reads = new AtomicInteger();
    var writes = new AtomicInteger();
    callbacks.add(
        tool(
            "readMultiFile",
            () -> {
              int line = reads.incrementAndGet();
              return PAGE.replace("\"startLine\":1", "\"startLine\":" + line)
                  .replace("docs/intro.md", newCoverage ? "docs/unseen.md" : "docs/intro.md");
            }));
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("writeMultiFile"));
    callbacks.add(
        tool(
            "writeMultiFile",
            () -> {
              try {
                Files.writeString(
                    root.resolve("review.md"),
                    "Partial assessment of delivered range " + reads.get());
                writes.incrementAndGet();
                return "[{\"path\":\"review.md\",\"success\":true}]";
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            }));
    var invocations = new AtomicInteger();
    var delegate =
        model(
            p -> {
              int n = invocations.incrementAndGet();
              assertThat(n).isLessThanOrEqualTo(13);
              if (writes.get() == 3) {
                assertThat(names(p)).contains("readMultiFile", "listFile");
                assertThat(c.detector().stagnationCount()).isZero();
                return new AssistantMessage(
                    "Three partial assessments saved; remaining review is incomplete");
              }
              // Reading a page must not force a report append. Finish the three-page unit
              // before recording one consolidated assessment, then continue the next unit.
              assertThat(names(p)).contains("readMultiFile");
              if (reads.get() < (writes.get() + 1) * 3) {
                return call(n, "readMultiFile", "{}");
              }
              assertThat(names(p)).contains("writeMultiFile");
              assertThat(p.getContents())
                  .contains("partial assessment", "Do not invent")
                  .doesNotContain("This boundary applies again after each subsequent content read");
              return call(
                  n,
                  "writeMultiFile",
                  "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
            });
    new StagnationChatModel(delegate)
        .stream(
                advised(
                    new Prompt(
                        initial.getInstructions(),
                        options.mutate().toolCallbacks(callbacks).build())))
            .blockLast();
    assertThat(reads).hasValue(9);
    assertThat(writes).hasValue(3);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "{\"error\":\"failed\"}",
        "malformed",
        "[{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"startLine\":1,\"content\":[]}]"
      })
  void unsuccessfulBodyReadDoesNotWithholdTheNextRead(String result) throws Exception {
    var c = context(0, 2, 8, true);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("readMultiFile"));
    callbacks.add(tool("readMultiFile", () -> result));
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  assertThat(names(p)).contains("readMultiFile");
                  if (calls.incrementAndGet() == 1) return call(1, "readMultiFile", "{}");
                  return new AssistantMessage("Missing evidence remains unresolved");
                }))
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
  }

  @Test
  void failedWriteAndAnnotationCannotUnlockMoreBodyReads() throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("writeMultiFile"));
    callbacks.add(tool("writeMultiFile", () -> "{\"success\":false}"));
    callbacks.add(tool("checkpointAnnotate", () -> "{\"success\":true}"));
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(
                            p -> {
                              int n = calls.incrementAndGet();
                              assertThat(names(p)).doesNotContain("readMultiFile");
                              return call(
                                  n,
                                  n == 1 ? "writeMultiFile" : "checkpointAnnotate",
                                  n == 1
                                      ? "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}"
                                      : "{}");
                            }))
                    .stream(
                            new Prompt(
                                initial.getInstructions(),
                                options.mutate().toolCallbacks(callbacks).build()))
                        .blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(c.detector().stagnationCount()).isEqualTo(4);
    assertThat(calls).hasValue(4);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
  }

  @Test
  void aReadAfterWorkInTheSameBatchStillNeedsAssessment() throws Exception {
    var c = context(0, 2, 8, true);
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(
                            p -> {
                              if (calls.incrementAndGet() == 1)
                                return AssistantMessage.builder()
                                    .toolCalls(
                                        List.of(
                                            new AssistantMessage.ToolCall(
                                                "write",
                                                "function",
                                                "writeMultiFile",
                                                "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}"),
                                            new AssistantMessage.ToolCall(
                                                "read", "function", "readMultiFile", "{}")))
                                    .build();
                              assertThat(c.hasPendingResumeContentWork()).isTrue();
                              if (calls.get() == 2) assertThat(names(p)).contains("readMultiFile");
                              else assertThat(names(p)).doesNotContain("readMultiFile");
                              assertThat(names(p)).contains("writeMultiFile");
                              return new AssistantMessage(
                                  "The newly read range still needs assessment");
                            }))
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(c.detector().stagnationCount()).isEqualTo(5);
    assertThat(c.needsResumeContentWork()).isTrue();
  }

  @Test
  void modelCannotExecuteAHiddenContentCallback() throws Exception {
    var c = context(0, 2, 8, true);
    fillResumeBatch(c);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    callbacks.removeIf(t -> t.getToolDefinition().name().equals("readMultiFile"));
    var reads = new AtomicInteger();
    callbacks.add(
        tool(
            "readMultiFile",
            () -> {
              reads.incrementAndGet();
              return PAGE;
            }));
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(p -> call(calls.incrementAndGet(), "readMultiFile", "{}")))
                    .stream(
                            new Prompt(
                                initial.getInstructions(),
                                options.mutate().toolCallbacks(callbacks).build()))
                        .blockLast())
        .isInstanceOf(RuntimeException.class);
    assertThat(reads).hasValue(0);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ordinary", "readOnly", "replanRemaining", "restorationRemaining"})
  void contentBoundaryDoesNotAffectOrdinaryOrUnverifiedExecutions(String condition)
      throws Exception {
    var c =
        context(
            0,
            condition.equals("replanRemaining") ? 1 : 2,
            condition.equals("restorationRemaining") ? 6 : 8,
            !condition.equals("ordinary"));
    if (condition.equals("readOnly") || condition.equals("conversation"))
      c.setRunContext(
          new AgentRunContext(
              "resume",
              "conversation",
              root,
              "project",
              AgentRunContext.RequestSource.SHELL,
              condition.equals("readOnly")
                  ? AgentRunContext.Mode.READ_ONLY
                  : AgentRunContext.Mode.CONVERSATION,
              false));
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  assertThat(names(p)).contains("readMultiFile");
                  if (calls.incrementAndGet() == 1) return call(1, "readMultiFile", "{}");
                  return new AssistantMessage("Ordinary content use");
                }))
        .stream(
                prompt(
                    c,
                    !condition.equals("noInventory"),
                    condition.equals("wrongOwner") ? "different" : "resume",
                    new AtomicInteger()))
            .blockLast();
  }

  @ParameterizedTest
  @ValueSource(strings = {"readReviewFile", "readRawToolResult"})
  void otherContentReadersAlsoAllowWorkUnitConsolidation(String reader) throws Exception {
    var c = context(0, 2, 8, true);
    var initial = prompt(c, true, "resume", new AtomicInteger());
    var options = (ToolCallingChatOptions) initial.getOptions();
    var callbacks = new ArrayList<>(options.getToolCallbacks());
    String result =
        reader.equals("readReviewFile")
            ? "{\"range\":{\"path\":\"docs/intro.md\",\"version\":\"v1\",\"span\":{\"start\":0,\"end\":5}},\"content\":\"known\"}"
            : "toolName: readMultiFile\ntoolCallId: original\nrawResultRef: 00000000-0000-0000-0000-000000000001\noffset: 0\ntotalCharacters: 5\nnextOffset: 5\nhasMore: false\nknown";
    callbacks.add(tool(reader, () -> result));
    var calls = new AtomicInteger();
    new StagnationChatModel(
            model(
                p -> {
                  int n = calls.incrementAndGet();
                  if (n == 1)
                    return call(
                        n,
                        reader,
                        reader.equals("readRawToolResult")
                            ? "{\"rawResultRef\":\"00000000-0000-0000-0000-000000000001\",\"offset\":0,\"limit\":5}"
                            : "{}");
                  if (n == 2) {
                    assertThat(names(p)).contains(reader, "readMultiFile");
                    return call(
                        n,
                        "writeMultiFile",
                        "{\"files\":[{\"path\":\"review.md\",\"content\":\"fixture assessment\"}]}");
                  }
                  assertThat(names(p)).contains(reader, "readMultiFile");
                  return new AssistantMessage("Partial evidence used");
                }))
        .stream(
                new Prompt(
                    initial.getInstructions(), options.mutate().toolCallbacks(callbacks).build()))
            .blockLast();
  }

  @Test
  void olderRestoredHandoffCannotOverrideCurrentUnverifiedInventory() throws Exception {
    var c = context(7, 2, 8, true);
    var current = prompt(c, false, "resume", new AtomicInteger());
    var messages = new ArrayList<>(current.getInstructions());
    messages.add(
        1,
        SystemMessage.builder()
            .text("Older saved continuation handoff")
            .metadata(
                Map.of(
                    "rei.resumeContext",
                    true,
                    "rei.resumeRunId",
                    "resume",
                    "rei.knownFileInventory",
                    true))
            .build());
    var delegate =
        model(
            p -> {
              assertThat(names(p))
                  .contains(
                      "listFile",
                      "findFile",
                      "countFileLines",
                      "checkpointShow",
                      "checkpointInspect");
              throw new java.util.concurrent.CancellationException();
            });
    assertThatThrownBy(
            () ->
                new StagnationChatModel(delegate)
                    .stream(new Prompt(messages, current.getOptions())).blockLast())
        .isInstanceOf(java.util.concurrent.CancellationException.class);
  }

  @Test
  void duplicateBodyReadsStopAtTheUnitBoundaryWithoutRefundingAnyBudget() throws Exception {
    var c = context(7, 2, 8, true);
    assertThatThrownBy(
            () ->
                new StagnationChatModel(
                        model(
                            p ->
                                names(p).contains("readMultiFile")
                                    ? call(1, "readMultiFile", "{}")
                                    : new AssistantMessage("No outcome can be confirmed")))
                    .stream(prompt(c, true, "resume", new AtomicInteger())).blockLast())
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(c.detector().stagnationCount()).isEqualTo(19);
    assertThat(c.budgetSnapshot().stagnationReplans()).isEqualTo(2);
    assertThat(c.progressSnapshot().continuity().evaluator().restorationReads()).isEqualTo(8);
  }

  @Test
  void modelCannotExecuteAHiddenDiscoveryCallback() throws Exception {
    var c = context(7, 2, 8, true);
    var executed = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new StagnationChatModel(model(p -> call(1, "listFile", "{}")))
                    .stream(prompt(c, true, "resume", executed)).blockLast())
        .isInstanceOf(RuntimeException.class);
    assertThat(executed).hasValue(0);
  }
}
