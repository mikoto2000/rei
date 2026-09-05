package dev.mikoto2000.rei.ui.shell;

import java.time.Duration;
import java.time.Instant;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.BackgroundProcessCompletedPayload;
import dev.mikoto2000.rei.event.BackgroundProcessFailedPayload;
import dev.mikoto2000.rei.event.BackgroundProcessKilledPayload;
import dev.mikoto2000.rei.event.BackgroundProcessStartedPayload;
import dev.mikoto2000.rei.event.CheckpointSavedPayload;
import dev.mikoto2000.rei.event.ContextBudgetEvaluatedPayload;
import dev.mikoto2000.rei.event.ContextBudgetTrimmedPayload;
import dev.mikoto2000.rei.event.ContextInjectedPayload;
import dev.mikoto2000.rei.event.FileCreatedPayload;
import dev.mikoto2000.rei.event.FileDeletedPayload;
import dev.mikoto2000.rei.event.FileModifiedPayload;
import dev.mikoto2000.rei.event.FileSummaryInvalidatedPayload;
import dev.mikoto2000.rei.event.FileSummarySavedPayload;
import dev.mikoto2000.rei.event.FileSummaryStaleSkippedPayload;
import dev.mikoto2000.rei.event.LlmRequestFailedPayload;
import dev.mikoto2000.rei.event.LlmRequestStartedPayload;
import dev.mikoto2000.rei.event.LlmResponseCompletedPayload;
import dev.mikoto2000.rei.event.LlmResponseFirstTokenPayload;
import dev.mikoto2000.rei.event.MessageCompletedPayload;
import dev.mikoto2000.rei.event.MessageDeltaPayload;
import dev.mikoto2000.rei.event.MessageStartedPayload;
import dev.mikoto2000.rei.event.SkillSelectionCompletedPayload;
import dev.mikoto2000.rei.event.SkillSelectionFailedPayload;
import dev.mikoto2000.rei.event.SkillRoutingCompletedPayload;
import dev.mikoto2000.rei.event.SkillRoutingFailedPayload;
import dev.mikoto2000.rei.event.SkillRoutingStartedPayload;
import dev.mikoto2000.rei.event.SkillCandidatesEvaluatedPayload;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;
import dev.mikoto2000.rei.event.TopicCandidateGeneratedPayload;
import dev.mikoto2000.rei.event.TopicCandidateRejectedPayload;
import dev.mikoto2000.rei.event.TopicCandidateScoredPayload;
import dev.mikoto2000.rei.event.TopicCandidatesRefreshedPayload;
import dev.mikoto2000.rei.event.TopicAutoSpeakSuppressedPayload;
import dev.mikoto2000.rei.event.TopicGenerationCompletedPayload;
import dev.mikoto2000.rei.event.TopicGenerationFailedPayload;
import dev.mikoto2000.rei.event.TopicGenerationStartedPayload;
import dev.mikoto2000.rei.event.TopicIdleTriggerEvaluatedPayload;
import dev.mikoto2000.rei.event.TopicSelectedPayload;
import dev.mikoto2000.rei.event.TopicSpeakSkippedPayload;
import dev.mikoto2000.rei.event.TopicSpokenPayload;
import dev.mikoto2000.rei.event.ThinkingCompletedPayload;
import dev.mikoto2000.rei.event.ThinkingDeltaPayload;
import dev.mikoto2000.rei.event.ThinkingStartedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemAddedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemRemovedPayload;
import dev.mikoto2000.rei.event.WorkingSetContextInjectedPayload;
import dev.mikoto2000.rei.event.WorkingSetSearchCompletedPayload;
import dev.mikoto2000.rei.event.WorkingSetSearchStartedPayload;
import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;
import org.springframework.core.env.Environment;

/** Append-only Shell presentation adapter for Agent events. */
public final class ShellAgentEventRenderer implements AgentEventListener {
  private final ShellEventOutput output;
  private final TopicNotificationOptions topicNotificationOptions;
  private String assistantMessageId;
  private boolean assistantLineOpen;
  private boolean toolInterruptedMessage;
  private String thinkingId;
  private boolean thinkingLineOpen;
  private Instant lastThrottledTopicSummaryAt;

  public ShellAgentEventRenderer(ShellEventOutput output) {
    this(output, TopicNotificationOptions.summary());
  }

  public ShellAgentEventRenderer(ShellEventOutput output, TopicNotificationOptions topicNotificationOptions) {
    this.output = output;
    this.topicNotificationOptions = topicNotificationOptions == null
        ? TopicNotificationOptions.summary()
        : topicNotificationOptions;
  }

  @Override
  public synchronized void onEvent(AgentEvent event) {
    if (isTopicEvent(event) && topicNotificationOptions.verbosity() != TopicNotificationVerbosity.VERBOSE) {
      renderTopicSummary(event);
      output.flush();
      return;
    }
    switch (event.type()) {
      case AGENT_RUN_STARTED -> {
        closeAssistantLine();
        closeThinkingLine();
        assistantMessageId = null;
        thinkingId = null;
        toolInterruptedMessage = false;
        output.println("[agent] running");
      }
      case AGENT_RUN_COMPLETED -> {
        closeAssistantLine();
        closeThinkingLine();
        AgentRunCompletedPayload payload = (AgentRunCompletedPayload) event.payload();
        String tokens = payload.completionTokens() == null
            ? "tokens unavailable"
            : payload.completionTokens() + " tokens";
        String ttft = payload.timeToFirstTokenMillis() == null
            ? "TTFT unavailable"
            : "TTFT " + formatDecimal(payload.timeToFirstTokenMillis()) + " ms";
        String outputSpeed = payload.outputTokensPerSecond() == null
            ? "output speed unavailable"
            : "output " + formatDecimal(payload.outputTokensPerSecond()) + " tok/s";
        String endToEndSpeed = payload.endToEndTokensPerSecond() == null
            ? "end-to-end speed unavailable"
            : "end-to-end " + formatDecimal(payload.endToEndTokensPerSecond()) + " tok/s";
        output.println("[agent] completed (" + formatSeconds(payload.duration()) + " s, " + tokens + ", "
            + ttft + ", " + outputSpeed + ", " + endToEndSpeed + ")");
      }
      case AGENT_RUN_FAILED -> {
        closeAssistantLine();
        closeThinkingLine();
        AgentRunFailedPayload payload = (AgentRunFailedPayload) event.payload();
        output.println("[agent] failed: " + errorMessage(payload.error()));
      }
      case LLM_REQUEST_STARTED -> {
        closeAssistantLine();
        closeThinkingLine();
        LlmRequestStartedPayload payload = (LlmRequestStartedPayload) event.payload();
        output.println("[llm] request sent (" + payload.feature() + ")");
      }
      case LLM_REQUEST_FAILED -> {
        closeAssistantLine();
        closeThinkingLine();
        LlmRequestFailedPayload payload = (LlmRequestFailedPayload) event.payload();
        output.println("[llm] request failed (" + payload.durationMs() + " ms): " + errorMessage(payload.error()));
      }
      case LLM_RESPONSE_FIRST_TOKEN -> {
        closeAssistantLine();
        closeThinkingLine();
        LlmResponseFirstTokenPayload payload = (LlmResponseFirstTokenPayload) event.payload();
        output.println("[llm] first token (" + payload.durationMs() + " ms)");
      }
      case LLM_RESPONSE_COMPLETED -> {
        closeAssistantLine();
        closeThinkingLine();
        LlmResponseCompletedPayload payload = (LlmResponseCompletedPayload) event.payload();
        output.println("[llm] response received (" + payload.durationMs() + " ms)");
      }
      case MESSAGE_STARTED -> messageStarted((MessageStartedPayload) event.payload());
      case MESSAGE_DELTA -> messageDelta((MessageDeltaPayload) event.payload());
      case MESSAGE_COMPLETED -> messageCompleted((MessageCompletedPayload) event.payload());
      case MEMORY_CONSOLIDATION_SUGGESTED -> {
        closeAssistantLine();
        closeThinkingLine();
        output.println("[memory] 記憶整理を実行することをお勧めします。/memory consolidate を実行してください。");
      }
      case THINKING_STARTED -> thinkingStarted((ThinkingStartedPayload) event.payload());
      case THINKING_DELTA -> thinkingDelta((ThinkingDeltaPayload) event.payload());
      case THINKING_COMPLETED -> thinkingCompleted((ThinkingCompletedPayload) event.payload());
      case TOOL_STARTED -> {
        beforeTool();
        ToolStartedPayload payload = (ToolStartedPayload) event.payload();
        String arguments = oneLineSummary(payload.argumentsSummary());
        output.println("  → " + payload.toolName() + (arguments.isEmpty() ? "" : " " + arguments));
      }
      case TOOL_COMPLETED -> {
        beforeTool();
        ToolCompletedPayload payload = (ToolCompletedPayload) event.payload();
        output.println("  ✓ " + payload.toolName() + " (" + payload.duration() + " ms)");
      }
      case TOOL_FAILED -> {
        beforeTool();
        ToolFailedPayload payload = (ToolFailedPayload) event.payload();
        output.println("  ✗ " + payload.toolName() + ": " + errorMessage(payload.error()));
      }
      case SKILL_SELECTION_STARTED -> {
        closeAssistantLine();
        output.println("[skill] selecting");
      }
      case SKILL_SELECTION_COMPLETED -> {
        closeAssistantLine();
        SkillSelectionCompletedPayload payload = (SkillSelectionCompletedPayload) event.payload();
        payload.warnings().forEach(output::println);
        java.util.List<String> selected = new java.util.ArrayList<>();
        payload.explicitSkillNames().forEach(name -> selected.add(name + " (explicit)"));
        payload.implicitSkillNames().forEach(name -> selected.add(name + " (implicit)"));
        output.println("[skill] selected: " + (selected.isEmpty() ? "none" : String.join(", ", selected)));
      }
      case SKILL_SELECTION_FAILED -> {
        closeAssistantLine();
        SkillSelectionFailedPayload payload = (SkillSelectionFailedPayload) event.payload();
        output.println("[skill] selection failed: " + errorMessage(payload.error()));
      }
      case SKILL_ROUTING_STARTED -> {
        closeAssistantLine();
        SkillRoutingStartedPayload payload = (SkillRoutingStartedPayload) event.payload();
        String invocation = payload.routingInvocation() > 1 ? " (#" + payload.routingInvocation() + ")" : "";
        output.println("[skill] selecting from " + payload.candidateCount() + " skills..." + invocation);
      }
      case SKILL_ROUTING_COMPLETED -> {
        closeAssistantLine();
        SkillRoutingCompletedPayload payload = (SkillRoutingCompletedPayload) event.payload();
        payload.warnings().forEach(output::println);
        String selection = payload.selectedSkill() == null
            ? "no skill selected" : payload.selectedSkill() + " selected";
        output.println("[skill] " + selection + " from " + payload.candidateCount() + " skills ("
            + formatDuration(payload.durationMs()) + selectorSuffix(payload.selectorDurationMs()) + ")");
      }
      case SKILL_ROUTING_FAILED -> {
        closeAssistantLine();
        SkillRoutingFailedPayload payload = (SkillRoutingFailedPayload) event.payload();
        output.println("[skill] selection failed after " + formatDuration(payload.durationMs()) + ": "
            + oneLineSummary(errorMessage(payload.error())));
      }
      case SKILL_CANDIDATES_EVALUATED -> {
        closeAssistantLine();
        SkillCandidatesEvaluatedPayload payload = (SkillCandidatesEvaluatedPayload) event.payload();
        String actual = payload.selected() ? payload.actualSelectedSkill() : "none";
        String top5 = !payload.selected() ? "n/a" : Boolean.TRUE.equals(payload.top5Hit()) ? "hit" : "miss";
        output.println("[skill-candidate] " + payload.totalSkillCount() + " -> " + payload.candidateCount()
            + " skills (" + payload.durationMs() + "ms), actual=" + actual + ", top5=" + top5);
      }
      case WORKING_SET_ITEM_ADDED -> {
        closeAssistantLine();
        WorkingSetItemAddedPayload payload = (WorkingSetItemAddedPayload) event.payload();
        output.println("[working-set] + " + displayName(payload.identifier(), payload.path())
            + reasonSuffix(payload.reason()));
      }
      case WORKING_SET_ITEM_REMOVED -> {
        closeAssistantLine();
        WorkingSetItemRemovedPayload payload = (WorkingSetItemRemovedPayload) event.payload();
        output.println("[working-set] - " + displayName(null, payload.itemId()) + reasonSuffix(payload.reason()));
      }
      case WORKING_SET_SEARCH_STARTED -> {
        closeAssistantLine();
        WorkingSetSearchStartedPayload payload = (WorkingSetSearchStartedPayload) event.payload();
        output.println("[working-set] → search \"" + boundedSummary(payload.query(), 120).replace('"', '\'') + "\"");
      }
      case WORKING_SET_SEARCH_COMPLETED -> {
        closeAssistantLine();
        WorkingSetSearchCompletedPayload payload = (WorkingSetSearchCompletedPayload) event.payload();
        String alreadyPresent = payload.alreadyPresentCount() == 0
            ? "" : ", " + payload.alreadyPresentCount() + " already present";
        output.println("[working-set] ✓ " + payload.hitCount() + " hits → " + payload.candidateCount()
            + " candidates → " + payload.selectedCount() + " selected" + alreadyPresent
            + " (" + payload.durationMs() + " ms)");
      }
      case WORKING_SET_CONTEXT_INJECTED -> {
        closeAssistantLine();
        WorkingSetContextInjectedPayload payload = (WorkingSetContextInjectedPayload) event.payload();
        output.println("[working-set] injected " + payload.itemCount() + " items into context ("
            + payload.contextCharacters() + " chars)");
      }
      case CONTEXT_INJECTED -> {
        closeAssistantLine();
        ContextInjectedPayload payload = (ContextInjectedPayload) event.payload();
        String items = payload.itemCount() == null ? "" : ", " + payload.itemCount() + " items";
        output.println("[context] injected " + lower(payload.source()) + items + " ("
            + payload.contextCharacters() + " chars)");
      }
      case CONTEXT_BUDGET_EVALUATED -> {
        closeAssistantLine();
        ContextBudgetEvaluatedPayload payload = (ContextBudgetEvaluatedPayload) event.payload();
        output.println("[context] budget evaluated: " + payload.totalTokens() + "/" + payload.inputBudget()
            + " tokens, included " + payload.included().size() + ", dropped " + payload.dropped().size());
      }
      case CONTEXT_BUDGET_TRIMMED -> {
        closeAssistantLine();
        ContextBudgetTrimmedPayload payload = (ContextBudgetTrimmedPayload) event.payload();
        output.println("[context] budget trimmed: dropped " + String.join(", ", payload.dropped()));
      }
      case FILE_SUMMARY_SAVED -> {
        closeAssistantLine();
        FileSummarySavedPayload payload = (FileSummarySavedPayload) event.payload();
        output.println("[file-summary] saved " + displayName(null, payload.path()) + " ("
            + payload.summaryCharacters() + " chars)");
      }
      case FILE_SUMMARY_INVALIDATED -> {
        closeAssistantLine();
        FileSummaryInvalidatedPayload payload = (FileSummaryInvalidatedPayload) event.payload();
        output.println("[file-summary] invalidated " + displayName(null, payload.path()));
      }
      case FILE_SUMMARY_STALE_SKIPPED -> {
        closeAssistantLine();
        FileSummaryStaleSkippedPayload payload = (FileSummaryStaleSkippedPayload) event.payload();
        output.println("[file-summary] skipped stale " + displayName(null, payload.path()));
      }
      case CHECKPOINT_SAVED -> {
        closeAssistantLine();
        CheckpointSavedPayload payload = (CheckpointSavedPayload) event.payload();
        output.println("[checkpoint] saved " + valueOr(payload.taskId(), "task") + reasonSuffix(payload.reason())
            + ", " + payload.workingFileCount() + " files");
      }
      case TOPIC_GENERATION_STARTED -> {
        closeAssistantLine();
        TopicGenerationStartedPayload payload = (TopicGenerationStartedPayload) event.payload();
        output.println("[topic] generation started");
        output.println("        id: " + payload.topicGenerationId());
      }
      case TOPIC_IDLE_TRIGGER_EVALUATED -> {
        closeAssistantLine();
        TopicIdleTriggerEvaluatedPayload payload = (TopicIdleTriggerEvaluatedPayload) event.payload();
        output.println("[topic] idle trigger " + (payload.accepted() ? "accepted" : "skipped"));
        output.println("        idle: " + formatDuration(payload.idleDurationMs()));
        output.println("        required: " + formatDuration(payload.requiredIdleMs()));
        if (payload.rejectReason() != null) {
          output.println("        reason: " + payload.rejectReason());
        }
      }
      case TOPIC_CANDIDATES_REFRESHED -> {
        closeAssistantLine();
        TopicCandidatesRefreshedPayload payload = (TopicCandidatesRefreshedPayload) event.payload();
        output.println("[topic] candidates refreshed");
        output.println("        candidates: " + payload.candidateCount());
      }
      case TOPIC_CANDIDATE_GENERATED -> {
        closeAssistantLine();
        TopicCandidateGeneratedPayload payload = (TopicCandidateGeneratedPayload) event.payload();
        output.println("[topic] candidate");
        output.println("        id: " + payload.candidateId());
        output.println("        type: " + lower(payload.topicType()));
        output.println("        source: " + lower(payload.source()));
        output.println("        topic: " + oneLineSummary(payload.topicSummary()));
        output.println("        reason: " + oneLineSummary(payload.reasonSummary()));
      }
      case TOPIC_CANDIDATE_SCORED -> {
        closeAssistantLine();
        TopicCandidateScoredPayload payload = (TopicCandidateScoredPayload) event.payload();
        output.println("[topic] scored");
        output.println("        id: " + payload.candidateId());
        output.println("        score: " + formatNullableScore(payload.score() == null ? null : payload.score().finalScore()));
        if (payload.score() != null) {
          output.println("        priority: +" + formatNullableScore(payload.score().priorityContribution()));
          output.println("        freshness: +" + formatNullableScore(payload.score().freshnessContribution()));
          output.println("        usefulness: +" + formatNullableScore(payload.score().usefulnessContribution()));
          output.println("        confidence: +" + formatNullableScore(payload.score().confidenceContribution()));
          output.println("        intrusiveness: -" + formatNullableScore(payload.score().intrusivenessPenalty()));
          output.println("        repetition: -" + formatNullableScore(payload.score().repetitionPenalty()));
        }
      }
      case TOPIC_CANDIDATE_REJECTED -> {
        closeAssistantLine();
        TopicCandidateRejectedPayload payload = (TopicCandidateRejectedPayload) event.payload();
        output.println("[topic] rejected");
        output.println("        id: " + payload.candidateId());
        output.println("        reason: " + payload.reason());
        output.println("        score: " + formatNullableScore(payload.score()));
      }
      case TOPIC_SELECTED -> {
        closeAssistantLine();
        TopicSelectedPayload payload = (TopicSelectedPayload) event.payload();
        output.println("[topic] selected");
        output.println("        id: " + payload.candidateId());
        output.println("        score: " + formatNullableScore(payload.score()));
        output.println("        rank: " + payload.rank());
      }
      case TOPIC_SPOKEN -> {
        closeAssistantLine();
        TopicSpokenPayload payload = (TopicSpokenPayload) event.payload();
        output.println("[topic] spoken");
        output.println("        id: " + payload.candidateId());
        output.println("        message: " + payload.messageId());
      }
      case TOPIC_SPEAK_SKIPPED -> {
        closeAssistantLine();
        TopicSpeakSkippedPayload payload = (TopicSpeakSkippedPayload) event.payload();
        output.println("[topic] speak skipped");
        output.println("        id: " + valueOr(payload.candidateId(), "none"));
        output.println("        reason: " + payload.reason());
        if (payload.nextSpeakAllowedAt() != null) {
          output.println("        nextAllowedAt: " + payload.nextSpeakAllowedAt());
        }
      }
      case TOPIC_AUTO_SPEAK_SUPPRESSED -> {
        closeAssistantLine();
        TopicAutoSpeakSuppressedPayload payload = (TopicAutoSpeakSuppressedPayload) event.payload();
        output.println("[topic] auto speak suppressed");
        output.println("        reason: " + oneLineSummary(payload.reason()));
      }
      case TOPIC_GENERATION_COMPLETED -> {
        closeAssistantLine();
        TopicGenerationCompletedPayload payload = (TopicGenerationCompletedPayload) event.payload();
        output.println("[topic] generation completed");
        output.println("        candidates: " + payload.candidateCount());
        output.println("        scored: " + payload.scoredCount());
        output.println("        rejected: " + payload.rejectedCount());
        output.println("        selected: " + valueOr(payload.selectedCandidateId(), "none"));
        output.println("        spoken: " + payload.spoken());
        output.println("        duration: " + payload.durationMs() + "ms");
      }
      case TOPIC_GENERATION_FAILED -> {
        closeAssistantLine();
        TopicGenerationFailedPayload payload = (TopicGenerationFailedPayload) event.payload();
        output.println("[topic] generation failed");
        output.println("        stage: " + payload.stage());
        output.println("        error: " + oneLineSummary(payload.errorSummary()));
      }
      case FILE_CREATED -> {
        closeAssistantLine();
        FileCreatedPayload payload = (FileCreatedPayload) event.payload();
        output.println("[file] created " + displayName(null, payload.path()));
      }
      case FILE_MODIFIED -> {
        closeAssistantLine();
        FileModifiedPayload payload = (FileModifiedPayload) event.payload();
        output.println("[file] modified " + displayName(null, payload.path()) + lineDeltaSuffix(payload));
      }
      case FILE_DELETED -> {
        closeAssistantLine();
        FileDeletedPayload payload = (FileDeletedPayload) event.payload();
        output.println("[file] deleted " + displayName(null, payload.path()));
      }
      case BACKGROUND_PROCESS_STARTED -> {
        closeAssistantLine();
        BackgroundProcessStartedPayload payload = (BackgroundProcessStartedPayload) event.payload();
        output.println("[process] started " + payload.processId() + " pid=" + payload.pid());
      }
      case BACKGROUND_PROCESS_COMPLETED -> {
        closeAssistantLine();
        BackgroundProcessCompletedPayload payload = (BackgroundProcessCompletedPayload) event.payload();
        output.println("[process] completed " + payload.processId() + " exit=" + payload.exitCode()
            + " (" + formatDecimal(payload.elapsedSeconds()) + " s)");
      }
      case BACKGROUND_PROCESS_FAILED -> {
        closeAssistantLine();
        BackgroundProcessFailedPayload payload = (BackgroundProcessFailedPayload) event.payload();
        output.println("[process] failed " + payload.processId() + ": " + errorMessage(payload.error()));
      }
      case BACKGROUND_PROCESS_KILLED -> {
        closeAssistantLine();
        BackgroundProcessKilledPayload payload = (BackgroundProcessKilledPayload) event.payload();
        output.println("[process] killed " + payload.processId() + " exit=" + payload.exitCode()
            + " (" + formatDecimal(payload.elapsedSeconds()) + " s)");
      }
      default -> { }
    }
    output.flush();
  }

  private boolean isTopicEvent(AgentEvent event) {
    return event.type().name().startsWith("TOPIC_");
  }

  private void renderTopicSummary(AgentEvent event) {
    if (topicNotificationOptions.verbosity() == TopicNotificationVerbosity.QUIET) return;
    switch (event.type()) {
      case TOPIC_CANDIDATES_REFRESHED -> {
        closeAssistantLine();
        TopicCandidatesRefreshedPayload payload = (TopicCandidatesRefreshedPayload) event.payload();
        output.println("[topic] candidates refreshed: " + payload.candidateCount());
      }
      case TOPIC_SPOKEN -> {
        if (!allowThrottledTopicSummary(event)) return;
        closeAssistantLine();
        TopicSpokenPayload payload = (TopicSpokenPayload) event.payload();
        output.println("[topic] spoken: " + valueOr(payload.candidateId(), "none")
            + " -> " + payload.messageId());
      }
      case TOPIC_SPEAK_SKIPPED -> {
        TopicSpeakSkippedPayload payload = (TopicSpeakSkippedPayload) event.payload();
        if (payload.reason() == TopicSpeakSkipReason.COOLDOWN) return;
        if (!allowThrottledTopicSummary(event)) return;
        closeAssistantLine();
        output.println("[topic] speak skipped: " + payload.reason());
      }
      case TOPIC_AUTO_SPEAK_SUPPRESSED -> {
        if (!allowThrottledTopicSummary(event)) return;
        closeAssistantLine();
        TopicAutoSpeakSuppressedPayload payload = (TopicAutoSpeakSuppressedPayload) event.payload();
        output.println("[topic] auto speak suppressed: " + oneLineSummary(payload.reason()));
      }
      case TOPIC_IDLE_TRIGGER_EVALUATED -> {
        TopicIdleTriggerEvaluatedPayload payload = (TopicIdleTriggerEvaluatedPayload) event.payload();
        if (!payload.accepted() && !topicNotificationOptions.showIdleSkipped()) return;
        if (!allowThrottledTopicSummary(event)) return;
        closeAssistantLine();
        output.println("[topic] idle trigger " + (payload.accepted() ? "accepted" : "skipped"));
      }
      case TOPIC_GENERATION_FAILED -> {
        closeAssistantLine();
        TopicGenerationFailedPayload payload = (TopicGenerationFailedPayload) event.payload();
        output.println("[topic] generation failed: " + payload.stage() + " "
            + oneLineSummary(payload.errorSummary()));
      }
      default -> { }
    }
  }

  private boolean allowThrottledTopicSummary(AgentEvent event) {
    Duration interval = topicNotificationOptions.minimumInterval();
    if (interval == null || interval.isZero() || interval.isNegative()) return true;
    if (lastThrottledTopicSummaryAt == null
        || !event.timestamp().isBefore(lastThrottledTopicSummaryAt.plus(interval))) {
      lastThrottledTopicSummaryAt = event.timestamp();
      return true;
    }
    return false;
  }

  private void messageStarted(MessageStartedPayload payload) {
    if ("assistant".equalsIgnoreCase(payload.role())) {
      closeThinkingLine();
      assistantMessageId = payload.messageId();
      output.println("=== answer ===");
    }
  }

  private void thinkingStarted(ThinkingStartedPayload payload) {
    closeAssistantLine();
    thinkingId = payload.thinkingId();
    thinkingLineOpen = false;
    output.println("=== thinking ===");
  }

  private void thinkingDelta(ThinkingDeltaPayload payload) {
    if (!payload.thinkingId().equals(thinkingId) || payload.delta() == null) return;
    output.print(payload.delta());
    thinkingLineOpen = true;
  }

  private void thinkingCompleted(ThinkingCompletedPayload payload) {
    if (!payload.thinkingId().equals(thinkingId)) return;
    closeThinkingLine();
    thinkingId = null;
  }

  private void messageDelta(MessageDeltaPayload payload) {
    if (!payload.messageId().equals(assistantMessageId) || payload.delta() == null) return;
    if (toolInterruptedMessage) {
      output.println("");
      toolInterruptedMessage = false;
    }
    output.print(payload.delta());
    assistantLineOpen = true;
  }

  private void messageCompleted(MessageCompletedPayload payload) {
    if (!payload.messageId().equals(assistantMessageId)) return;
    closeAssistantLine();
    assistantMessageId = null;
    toolInterruptedMessage = false;
  }

  private void beforeTool() {
    if (assistantMessageId != null) toolInterruptedMessage = true;
    closeAssistantLine();
  }

  private void closeAssistantLine() {
    if (assistantLineOpen) {
      output.println("");
      assistantLineOpen = false;
    }
  }

  private void closeThinkingLine() {
    if (thinkingLineOpen) {
      output.println("");
      thinkingLineOpen = false;
    }
  }

  private String errorMessage(dev.mikoto2000.rei.event.ErrorInformation error) {
    if (error == null || error.message() == null || error.message().isBlank()) return "unknown error";
    return error.message();
  }

  private String formatSeconds(long durationMillis) {
    return formatDecimal(durationMillis / 1_000.0d);
  }

  private String formatDuration(long durationMillis) {
    return durationMillis < 1_000L ? durationMillis + "ms"
        : String.format(java.util.Locale.ROOT, "%.2fs", durationMillis / 1_000.0d);
  }

  private String selectorSuffix(Long durationMillis) {
    return durationMillis == null ? "" : ", selector " + formatDuration(durationMillis);
  }

  private String formatDecimal(double value) {
    return String.format(java.util.Locale.ROOT, "%.1f", value);
  }

  private String formatNullableScore(Double value) {
    return value == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  private String lower(String value) {
    return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
  }

  private String oneLineSummary(String value) {
    if (value == null || value.isBlank()) return "";
    StringBuilder sanitized = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      sanitized.append(Character.isISOControl(character) ? ' ' : character);
    }
    return sanitized.toString().replaceAll("\\s+", " ").trim();
  }

  private String displayName(String identifier, String path) {
    String value = identifier == null || identifier.isBlank() ? path : identifier;
    if (value == null || value.isBlank()) return "item";
    String normalized = value.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return separator < 0 ? normalized : normalized.substring(separator + 1);
  }

  private String reasonSuffix(String reason) {
    String safe = oneLineSummary(reason);
    if (safe.isEmpty()) return "";
    int maxLength = 120;
    if (safe.length() > maxLength) safe = safe.substring(0, maxLength - 1) + "…";
    return " (" + safe + ")";
  }

  private String boundedSummary(String value, int maxLength) {
    String safe = oneLineSummary(value);
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
  }

  private String valueOr(String preferred, String fallback) {
    return preferred == null ? fallback : preferred;
  }

  private int valueOr(Integer preferred, int fallback) {
    return preferred == null ? fallback : preferred;
  }

  private String lineDeltaSuffix(FileModifiedPayload payload) {
    if (payload.addedLines() == null && payload.removedLines() == null) return "";
    return " (+" + valueOr(payload.addedLines(), 0) + "/-" + valueOr(payload.removedLines(), 0) + ")";
  }

  public enum TopicNotificationVerbosity {
    QUIET,
    SUMMARY,
    VERBOSE
  }

  public record TopicNotificationOptions(
      TopicNotificationVerbosity verbosity,
      Duration minimumInterval,
      boolean showIdleSkipped) {

    public static TopicNotificationOptions summary() {
      return new TopicNotificationOptions(TopicNotificationVerbosity.SUMMARY, Duration.ofMinutes(30), false);
    }

    public static TopicNotificationOptions verbose() {
      return new TopicNotificationOptions(TopicNotificationVerbosity.VERBOSE, Duration.ZERO, true);
    }

    public static TopicNotificationOptions from(Environment environment) {
      if (environment == null) return summary();
      String verbosityValue = environment.getProperty(
          "rei.topic-generator.shell-notification.verbosity", "summary");
      TopicNotificationVerbosity verbosity = parseVerbosity(verbosityValue);
      Duration minimumInterval = environment.getProperty(
          "rei.topic-generator.shell-notification.minimum-interval", Duration.class, Duration.ofMinutes(30));
      boolean showIdleSkipped = environment.getProperty(
          "rei.topic-generator.shell-notification.show-idle-skipped", Boolean.class, Boolean.FALSE);
      return new TopicNotificationOptions(verbosity, minimumInterval, showIdleSkipped);
    }

    private static TopicNotificationVerbosity parseVerbosity(String value) {
      if (value == null || value.isBlank()) return TopicNotificationVerbosity.SUMMARY;
      try {
        return TopicNotificationVerbosity.valueOf(value.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        return TopicNotificationVerbosity.SUMMARY;
      }
    }
  }
}
