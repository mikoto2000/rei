package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import dev.mikoto2000.rei.event.BackgroundProcessCompletedPayload;
import dev.mikoto2000.rei.event.BackgroundProcessFailedPayload;
import dev.mikoto2000.rei.event.BackgroundProcessKilledPayload;
import dev.mikoto2000.rei.event.BackgroundProcessStartedPayload;
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
import dev.mikoto2000.rei.event.SkillCandidatesEvaluatedPayload;
import dev.mikoto2000.rei.event.SkillRoutingCompletedPayload;
import dev.mikoto2000.rei.event.SkillRoutingFailedPayload;
import dev.mikoto2000.rei.event.SkillRoutingStartedPayload;
import dev.mikoto2000.rei.event.SkillSelectionCompletedPayload;
import dev.mikoto2000.rei.event.SkillSelectionFailedPayload;
import dev.mikoto2000.rei.event.ThinkingCompletedPayload;
import dev.mikoto2000.rei.event.ThinkingDeltaPayload;
import dev.mikoto2000.rei.event.ThinkingStartedPayload;
import dev.mikoto2000.rei.event.ToolCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import dev.mikoto2000.rei.event.ToolStartedPayload;
import dev.mikoto2000.rei.event.TopicAutoSpeakSuppressedPayload;
import dev.mikoto2000.rei.event.TopicCandidateGeneratedPayload;
import dev.mikoto2000.rei.event.TopicCandidateRejectedPayload;
import dev.mikoto2000.rei.event.TopicCandidateScoredPayload;
import dev.mikoto2000.rei.event.TopicCandidatesRefreshedPayload;
import dev.mikoto2000.rei.event.TopicGenerationCompletedPayload;
import dev.mikoto2000.rei.event.TopicGenerationFailedPayload;
import dev.mikoto2000.rei.event.TopicGenerationStartedPayload;
import dev.mikoto2000.rei.event.TopicIdleTriggerEvaluatedPayload;
import dev.mikoto2000.rei.event.TopicSelectedPayload;
import dev.mikoto2000.rei.event.TopicSpeakSkippedPayload;
import dev.mikoto2000.rei.event.TopicSpokenPayload;
import dev.mikoto2000.rei.event.WorkingSetContextInjectedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemAddedPayload;
import dev.mikoto2000.rei.event.WorkingSetItemRemovedPayload;
import dev.mikoto2000.rei.event.WorkingSetSearchCompletedPayload;
import dev.mikoto2000.rei.event.WorkingSetSearchStartedPayload;
import dev.mikoto2000.rei.topic.TopicSpeakSkipReason;
import java.time.Duration;
import java.time.Instant;
import org.springframework.core.env.Environment;

/**
 * シェルエージェントイベントを画面やターミナルへ表示する。
 *
 * <p>Append-only Shell presentation adapter for Agent events.
 */
public final class ShellAgentEventRenderer implements AgentEventListener {
  /** 出力。 */
  private final ShellEventOutput output;

  /** 話題通知選択可能な設定。 */
  private final TopicNotificationOptions topicNotificationOptions;

  /** アシスタントメッセージ識別子。 */
  private String assistantMessageId;

  /** アシスタント行開始。 */
  private boolean assistantLineOpen;

  /** ツール中断されたメッセージ。 */
  private boolean toolInterruptedMessage;

  /** 推論識別子。 */
  private String thinkingId;

  /** 推論行開始。 */
  private boolean thinkingLineOpen;

  /** 待機中通知一覧。 */
  private final java.util.List<AgentEvent> pendingNotifications = new java.util.ArrayList<>();

  /** flushing通知一覧。 */
  private boolean flushingNotifications;

  /** 待機中言語モデルCompletions。 */
  private final java.util.List<AgentEvent> pendingLlmCompletions = new java.util.ArrayList<>();

  /**
   * ターミナルでの対話で受け渡す言語モデル要求キーを表す。
   *
   * @param runId 実行の識別子
   * @param requestId 要求の識別子
   */
  private record LlmRequestKey(String runId, String requestId) {}

  /** 言語モデル要求Features。 */
  private final java.util.Map<LlmRequestKey, String> llmRequestFeatures;

  /** 直近Throttled話題要約日時。 */
  private Instant lastThrottledTopicSummaryAt;

  /** 補助エージェント名前一覧。 */
  private final java.util.LinkedHashMap<String, String> subAgentNames =
      new java.util.LinkedHashMap<>();

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ShellAgentEventRenderer(ShellEventOutput output) {
    this(output, TopicNotificationOptions.summary());
  }

  /** ターミナルでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ShellAgentEventRenderer(
      ShellEventOutput output, TopicNotificationOptions topicNotificationOptions) {
    this(output, topicNotificationOptions, new java.util.HashMap<>());
  }

  /** 指定された値や利用先を使って、ShellAgentEventRendererを初期化する。 */
  private ShellAgentEventRenderer(
      ShellEventOutput output,
      TopicNotificationOptions topicNotificationOptions,
      java.util.Map<LlmRequestKey, String> llmRequestFeatures) {
    this.output = output;
    this.llmRequestFeatures = llmRequestFeatures;
    this.topicNotificationOptions =
        topicNotificationOptions == null
            ? TopicNotificationOptions.summary()
            : topicNotificationOptions;
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  @Override
  public synchronized void onEvent(AgentEvent event) {
    // Checkpoint bookkeeping must not interrupt answer/thinking streams, including child runs.
    switch (event.type()) {
      case CHECKPOINT_SAVED,
          CHECKPOINT_RECONCILED,
          CHECKPOINT_RESUMED,
          CHECKPOINT_REVISION_SAVED -> {
        return;
      }
      default -> {}
    }
    // Notifications may overtake downstream answer chunks. Preserve the text until
    // a stream boundary, including when a chunk ends in a newline or mid-link.
    if (!flushingNotifications && assistantMessageId != null && deferDuringAnswer(event)) {
      pendingNotifications.add(event);
      return;
    }
    if (event.payload()
        instanceof dev.mikoto2000.rei.event.ExternalAgentLifecyclePayload delegation) {
      closeAssistantLine();
      closeThinkingLine();
      output.println(
          "[delegation] "
              + delegation.agent()
              + " "
              + event.type().value().substring("delegation.".length())
              + ": "
              + delegation.summary());
      output.flush();
      return;
    }
    if (event.payload() instanceof dev.mikoto2000.rei.event.SubAgentLifecyclePayload child) {
      rememberSubAgent(child);
      closeAssistantLine();
      closeThinkingLine();
      output.println(
          "[subagent] "
              + child.agentId()
              + " "
              + child.status().toLowerCase(java.util.Locale.ROOT)
              + " ("
              + formatSeconds(child.duration())
              + " s)"
              + " ["
              + child.subAgentRunId()
              + "]");
      if (child.failureReason() != null)
        output.println(
            "[subagent] 原因: "
                + dev.mikoto2000.rei.event.CredentialRedactor.redact(child.failureReason()));
      output.flush();
      return;
    }
    if (isSubAgentEvent(event)) {
      // Child answer/thinking streams must not enter the parent's streamed message state.
      switch (event.type()) {
        case MESSAGE_STARTED,
            MESSAGE_DELTA,
            MESSAGE_COMPLETED,
            THINKING_STARTED,
            THINKING_DELTA,
            THINKING_COMPLETED -> {
          return;
        }
        default -> {}
      }
      closeAssistantLine();
      closeThinkingLine();
      String prefix = subAgentPrefix(event);
      new ShellAgentEventRenderer(
              new ShellEventOutput() {
                /** 利用者に結果を伝えるため、を出力する。 */
                public void print(String text) {
                  output.print(prefix + text);
                }

                /** ターミナルでの対話に関する改行付きの出力を処理する。 */
                public void println(String text) {
                  output.println(prefix + text);
                }

                /** ターミナルでの対話に関する保存の確定を処理する。 */
                public void flush() {
                  output.flush();
                }
              },
              topicNotificationOptions,
              llmRequestFeatures)
          .renderEvent(event);
      return;
    }
    renderEvent(event);
  }

  /** ターミナルでの対話において、deferDuring回答に該当するか判定する。 */
  private boolean deferDuringAnswer(AgentEvent event) {
    if (isSubAgentEvent(event)) return true;
    return switch (event.type()) {
      case MESSAGE_STARTED,
          MESSAGE_DELTA,
          MESSAGE_COMPLETED,
          THINKING_STARTED,
          THINKING_DELTA,
          THINKING_COMPLETED,
          AGENT_RUN_STARTED,
          AGENT_RUN_COMPLETED,
          AGENT_RUN_FAILED,
          AGENT_RUN_CANCELLED,
          TOOL_STARTED,
          TOOL_COMPLETED,
          TOOL_FAILED,
          LLM_RESPONSE_COMPLETED,
          ATTENTION_REQUIRED,
          APPLICATION_SHUTDOWN_STARTED ->
          false;
      default -> true;
    };
  }

  /** ターミナルでの対話において、補助エージェントイベントかどうかを判定する。 */
  private boolean isSubAgentEvent(AgentEvent event) {
    return event.sessionId() != null && event.sessionId().startsWith("subagent:");
  }

  /** ターミナルでの対話に関するremember補助エージェントを処理する。 */
  private void rememberSubAgent(dev.mikoto2000.rei.event.SubAgentLifecyclePayload child) {
    subAgentNames.put(child.subAgentRunId(), child.agentId());
    // Retain recent names for late events and replay without growing for the shell's lifetime.
    if (subAgentNames.size() > 128) subAgentNames.pollFirstEntry();
  }

  /** ターミナルでの対話で用いる補助エージェント接頭辞を返す。 */
  private String subAgentPrefix(AgentEvent event) {
    String runId =
        event.runId() == null ? event.sessionId().substring("subagent:".length()) : event.runId();
    String name = subAgentNames.get(runId);
    return "[subagent:" + (name == null ? "" : name + "/") + runId + "] ";
  }

  /** 利用者が内容を確認できるよう、イベントを表示する。 */
  private void renderEvent(AgentEvent event) {
    if (isTopicEvent(event)
        && topicNotificationOptions.verbosity() != TopicNotificationVerbosity.VERBOSE) {
      renderTopicSummary(event);
      output.flush();
      return;
    }
    switch (event.type()) {
      case AUTO_SLEEP_STOPPED -> {
        closeAssistantLine();
        closeThinkingLine();
        output.println(
            ((dev.mikoto2000.rei.event.MemoryLifecyclePayload) event.payload()).status());
      }
      case DEPENDENCY_FAILED, DEPENDENCY_CANCELLED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.DependencyStatusPayload) event.payload();
        output.println(
            "[dependency] "
                + payload.dependencyId()
                + " "
                + payload.state()
                + " 理由: "
                + dev.mikoto2000.rei.event.FailureMessages.text(payload.reason()));
      }
      case MEMORY_SLEEP_FAILED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.MemoryLifecyclePayload) event.payload();
        output.println("[memory.sleep] " + payload.status() + " (" + payload.sleepRunId() + ")");
      }
      case CONTEXT_COMPRESSION_FAILED, CONTEXT_COMPRESSION_SKIPPED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.ContextCompressionPayload) event.payload();
        String state =
            event.type() == dev.mikoto2000.rei.event.AgentEventType.CONTEXT_COMPRESSION_FAILED
                ? "failed"
                : "skipped";
        String details =
            payload.details() == null || payload.details().isBlank()
                ? ""
                : " (" + dev.mikoto2000.rei.event.FailureMessages.text(payload.details()) + ")";
        output.println(
            "[context.compression] "
                + state
                + ": "
                + dev.mikoto2000.rei.event.FailureMessages.text(payload.reason())
                + details);
      }
      case TASK_FAILED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.TaskFailedPayload) event.payload();
        output.println("[task] failed " + payload.taskId() + ": " + errorMessage(payload.error()));
      }
      case GOAL_UPDATED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.GoalLifecyclePayload) event.payload();
        output.println(
            "[goal] "
                + payload.goalId()
                + " "
                + payload.status()
                + " runs="
                + payload.attempts()
                + "/"
                + payload.maxRuns()
                + " llm="
                + payload.llmCallsUsed()
                + "/"
                + payload.maxLlmCalls());
        if (payload.reason() != null && !payload.reason().isBlank())
          output.println(
              "[goal] 理由: " + dev.mikoto2000.rei.event.FailureMessages.text(payload.reason()));
      }
      case ATTENTION_REQUIRED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.AttentionRequiredPayload) event.payload();
        if ("APPROVAL_REQUIRED".equals(payload.kind())) {
          output.approvalRequired(payload.message());
          var match =
              java.util.regex.Pattern.compile(
                      "(?m)^/approval approve ([0-9a-fA-F-]{36}) --continue$")
                  .matcher(payload.message());
          if (match.find())
            output.approvalActions(event.projectId(), event.sessionId(), match.group(1));
        } else
          output.println(
              "[attention] "
                  + payload.kind()
                  + ": "
                  + payload.message()
                  + " (/attention show "
                  + payload.attentionId()
                  + ")");
      }
      case WORK_CONTEXT_UPDATED, WORK_CONTEXT_UPDATE_FAILED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.WorkContextPayload) event.payload();
        output.println(
            "["
                + event.type().value()
                + "] "
                + payload.status()
                + " revision="
                + payload.revision());
      }
      case APPLICATION_SHUTDOWN_STARTED -> {
        closeAssistantLine();
        closeThinkingLine();
        output.println("[application] グレースフルシャットダウンを開始します。");
      }
      case COMPUTER_USE_PROGRESS -> {
        closeAssistantLine();
        closeThinkingLine();
        var progress = (dev.mikoto2000.rei.event.ComputerUseProgressPayload) event.payload();
        output.println(
            "[computer_use] step="
                + progress.step()
                + " "
                + progress.phase()
                + (progress.action() == null ? "" : " " + progress.action())
                + (progress.target() == null
                    ? ""
                    : " "
                        + progress.target()
                        + " ("
                        + progress.x()
                        + ","
                        + progress.y()
                        + ") confidence="
                        + progress.confidence())
                + (progress.reason() == null ? "" : " " + progress.reason()));
      }
      case EXECUTION_STARTED, EXECUTION_COMPLETED, EXECUTION_FAILED, EXECUTION_CANCELLED -> {
        renderBackgroundExecution(
            (dev.mikoto2000.rei.event.BackgroundExecutionPayload) event.payload(),
            event.projectId());
      }
      case HISTORY_SEARCH_COMPLETED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.HistorySearchCompletedPayload) event.payload();
        output.println(
            "[history.search] scope="
                + payload.scope()
                + " currentHits="
                + payload.currentProjectHitCount()
                + " crossProjectHits="
                + payload.crossProjectHitCount()
                + " projectsSearched="
                + payload.searchedProjectCount());
      }
      case USER_INTERVENTION_RECEIVED, USER_INTERVENTION_APPLIED -> {
        closeAssistantLine();
        output.println(
            event.type() == dev.mikoto2000.rei.event.AgentEventType.USER_INTERVENTION_RECEIVED
                ? "[user] guidance queued"
                : "[user] guidance applied");
      }
      case PROGRESS_DETECTED,
          STAGNATION_UPDATED,
          STAGNATION_DETECTED,
          STAGNATION_REPLAN_REQUESTED,
          STAGNATION_RECOVERED,
          STAGNATION_STOPPED -> {
        closeAssistantLine();
        closeThinkingLine();
        var payload = (dev.mikoto2000.rei.event.ExecutionProgressPayload) event.payload();
        String text =
            switch (event.type()) {
              case PROGRESS_DETECTED ->
                  "[progress] "
                      + payload.evidence().kind()
                      + ": "
                      + payload.evidence().description()
                      + " ("
                      + payload.evidence().source()
                      + ")";
              case STAGNATION_UPDATED ->
                  "waiting_for_dependency".equals(payload.reason())
                      ? "[waiting] awaiting dependency"
                      : "INFORMATION_UNKNOWN".equals(payload.reason())
                          ? "[stagnation] progress unknown: reason=INFORMATION_UNKNOWN ledgerStatus=UNKNOWN"
                          : "[stagnation] no progress: "
                              + payload.consecutiveNoProgressIterations()
                              + "/"
                              + payload.threshold();
              case STAGNATION_DETECTED -> "[stagnation] threshold reached";
              case STAGNATION_REPLAN_REQUESTED ->
                  "[stagnation] replanning ("
                      + payload.stagnationReplanCount()
                      + "/"
                      + payload.maxStagnationReplans()
                      + ")";
              case STAGNATION_RECOVERED -> "[stagnation] recovered: meaningful progress detected";
              default -> "[stagnation] repeated after replanning -> stopped (STAGNATED)";
            };
        output.println(text);
        if (java.util.Set.of(
                "REPEATED_READ",
                "REPEATED_SEARCH_OR_ACTION",
                "TOOL_FAILURE",
                "WORK_STALLED",
                "DUPLICATE_OR_UNSUPPORTED_INFORMATION")
            .contains(java.util.Objects.toString(payload.reason(), "")))
          output.println("[stagnation] reason=" + payload.reason() + " ledgerStatus=HEALTHY");
      }
      case AGENT_RUN_STARTED -> {
        closeAssistantLine();
        closeThinkingLine();
        flushLlmCompletions();
        assistantMessageId = null;
        thinkingId = null;
        toolInterruptedMessage = false;
        output.println("[agent] running");
      }
      case AGENT_RUN_COMPLETED -> {
        closeAssistantLine();
        closeThinkingLine();
        flushLlmCompletions();
        assistantMessageId = null;
        thinkingId = null;
        AgentRunCompletedPayload payload = (AgentRunCompletedPayload) event.payload();
        llmRequestFeatures
            .keySet()
            .removeIf(key -> java.util.Objects.equals(key.runId(), event.runId()));
        String tokens =
            payload.completionTokens() == null
                ? "tokens unavailable"
                : payload.completionTokens() + " tokens";
        String ttft =
            payload.timeToFirstTokenMillis() == null
                ? "TTFT unavailable"
                : "TTFT " + formatDecimal(payload.timeToFirstTokenMillis()) + " ms";
        String outputSpeed =
            payload.outputTokensPerSecond() == null
                ? "output speed unavailable"
                : "output " + formatDecimal(payload.outputTokensPerSecond()) + " tok/s";
        String endToEndSpeed =
            payload.endToEndTokensPerSecond() == null
                ? "end-to-end speed unavailable"
                : "end-to-end " + formatDecimal(payload.endToEndTokensPerSecond()) + " tok/s";
        output.println(
            "[agent] completed ("
                + formatSeconds(payload.duration())
                + " s, "
                + tokens
                + ", "
                + ttft
                + ", "
                + outputSpeed
                + ", "
                + endToEndSpeed
                + ")");
      }
      case AGENT_RUN_FAILED, AGENT_RUN_CANCELLED -> {
        closeAssistantLine();
        closeThinkingLine();
        flushLlmCompletions();
        assistantMessageId = null;
        thinkingId = null;
        AgentRunFailedPayload payload = (AgentRunFailedPayload) event.payload();
        llmRequestFeatures
            .keySet()
            .removeIf(key -> java.util.Objects.equals(key.runId(), event.runId()));
        output.println(
            event.type() == dev.mikoto2000.rei.event.AgentEventType.AGENT_RUN_CANCELLED
                    || payload.error() != null && "cancelled".equals(payload.error().code())
                ? "[agent] cancelled: " + errorMessage(payload.error())
                : "[agent] failed: " + errorMessage(payload.error()));
      }
      case LLM_REQUEST_STARTED -> {
        closeAssistantLine();
        closeThinkingLine();
        LlmRequestStartedPayload payload = (LlmRequestStartedPayload) event.payload();
        llmRequestFeatures.put(
            new LlmRequestKey(event.runId(), payload.requestId()), payload.feature());
        output.println("[llm] request sent (" + payload.feature() + ")");
      }
      case LLM_REQUEST_FAILED -> {
        closeAssistantLine();
        closeThinkingLine();
        LlmRequestFailedPayload payload = (LlmRequestFailedPayload) event.payload();
        llmRequestFeatures.remove(new LlmRequestKey(event.runId(), payload.requestId()));
        output.println(
            "[llm] request failed ("
                + payload.durationMs()
                + " ms): "
                + errorMessage(payload.error()));
      }
      case LLM_RESPONSE_FIRST_TOKEN -> {
        closeAssistantLine();
        closeThinkingLine();
        LlmResponseFirstTokenPayload payload = (LlmResponseFirstTokenPayload) event.payload();
        output.println("[llm] first token (" + payload.durationMs() + " ms)");
      }
      case LLM_RESPONSE_COMPLETED -> {
        // Model completion can overtake downstream chunks across a scheduler boundary.
        // Keep the notification until the consumer has finished rendering the stream.
        if (assistantMessageId != null || thinkingId != null) {
          pendingLlmCompletions.add(event);
          break;
        }
        closeAssistantLine();
        closeThinkingLine();
        renderLlmCompletion(event);
      }
      case MESSAGE_STARTED -> messageStarted((MessageStartedPayload) event.payload(), event);
      case MESSAGE_DELTA -> messageDelta((MessageDeltaPayload) event.payload());
      case MESSAGE_COMPLETED -> messageCompleted((MessageCompletedPayload) event.payload());
      case MEMORY_CONSOLIDATION_SUGGESTED -> {
        closeAssistantLine();
        closeThinkingLine();
        output.println("[memory] 記憶整理を実行することをお勧めします。/memory consolidate を実行してください。");
      }
      case THINKING_STARTED -> thinkingStarted((ThinkingStartedPayload) event.payload(), event);
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
        output.println(
            "[skill] selected: " + (selected.isEmpty() ? "none" : String.join(", ", selected)));
      }
      case SKILL_SELECTION_FAILED -> {
        closeAssistantLine();
        SkillSelectionFailedPayload payload = (SkillSelectionFailedPayload) event.payload();
        output.println("[skill] selection failed: " + errorMessage(payload.error()));
      }
      case SKILL_ROUTING_STARTED -> {
        closeAssistantLine();
        SkillRoutingStartedPayload payload = (SkillRoutingStartedPayload) event.payload();
        String invocation =
            payload.routingInvocation() > 1 ? " (#" + payload.routingInvocation() + ")" : "";
        output.println(
            "[skill] selecting from " + payload.candidateCount() + " skills..." + invocation);
      }
      case SKILL_ROUTING_COMPLETED -> {
        closeAssistantLine();
        SkillRoutingCompletedPayload payload = (SkillRoutingCompletedPayload) event.payload();
        payload.warnings().forEach(output::println);
        String selection =
            payload.selectedSkill() == null
                ? "no skill selected"
                : payload.selectedSkill() + " selected";
        output.println(
            "[skill] "
                + selection
                + " from "
                + payload.candidateCount()
                + " skills ("
                + formatDuration(payload.durationMs())
                + selectorSuffix(payload.selectorDurationMs())
                + ")");
      }
      case SKILL_ROUTING_FAILED -> {
        closeAssistantLine();
        SkillRoutingFailedPayload payload = (SkillRoutingFailedPayload) event.payload();
        output.println(
            "[skill] selection failed after "
                + formatDuration(payload.durationMs())
                + ": "
                + oneLineSummary(errorMessage(payload.error())));
      }
      case SKILL_CANDIDATES_EVALUATED -> {
        closeAssistantLine();
        SkillCandidatesEvaluatedPayload payload = (SkillCandidatesEvaluatedPayload) event.payload();
        String actual = payload.selected() ? payload.actualSelectedSkill() : "none";
        String top5 =
            !payload.selected() ? "n/a" : Boolean.TRUE.equals(payload.top5Hit()) ? "hit" : "miss";
        output.println(
            "[skill-candidate] "
                + payload.totalSkillCount()
                + " -> "
                + payload.candidateCount()
                + " skills ("
                + payload.durationMs()
                + "ms), actual="
                + actual
                + ", top5="
                + top5);
      }
      case WORKING_SET_ITEM_ADDED -> {
        closeAssistantLine();
        WorkingSetItemAddedPayload payload = (WorkingSetItemAddedPayload) event.payload();
        output.println(
            "[working-set] + "
                + displayName(payload.identifier(), payload.path())
                + reasonSuffix(payload.reason()));
      }
      case WORKING_SET_ITEM_REMOVED -> {
        closeAssistantLine();
        WorkingSetItemRemovedPayload payload = (WorkingSetItemRemovedPayload) event.payload();
        output.println(
            "[working-set] - "
                + displayName(null, payload.itemId())
                + reasonSuffix(payload.reason()));
      }
      case WORKING_SET_SEARCH_STARTED -> {
        closeAssistantLine();
        WorkingSetSearchStartedPayload payload = (WorkingSetSearchStartedPayload) event.payload();
        output.println(
            "[working-set] → search \""
                + boundedSummary(payload.query(), 120).replace('"', '\'')
                + "\"");
      }
      case WORKING_SET_SEARCH_COMPLETED -> {
        closeAssistantLine();
        WorkingSetSearchCompletedPayload payload =
            (WorkingSetSearchCompletedPayload) event.payload();
        String alreadyPresent =
            payload.alreadyPresentCount() == 0
                ? ""
                : ", " + payload.alreadyPresentCount() + " already present";
        output.println(
            "[working-set] ✓ "
                + payload.hitCount()
                + " hits → "
                + payload.candidateCount()
                + " candidates → "
                + payload.selectedCount()
                + " selected"
                + alreadyPresent
                + " ("
                + payload.durationMs()
                + " ms)");
      }
      case WORKING_SET_CONTEXT_INJECTED -> {
        closeAssistantLine();
        WorkingSetContextInjectedPayload payload =
            (WorkingSetContextInjectedPayload) event.payload();
        output.println(
            "[working-set] injected "
                + payload.itemCount()
                + " items into context ("
                + payload.contextCharacters()
                + " chars)");
      }
      case CONTEXT_INJECTED -> {
        closeAssistantLine();
        ContextInjectedPayload payload = (ContextInjectedPayload) event.payload();
        String items = payload.itemCount() == null ? "" : ", " + payload.itemCount() + " items";
        output.println(
            "[context] injected "
                + lower(payload.source())
                + items
                + " ("
                + payload.contextCharacters()
                + " chars)");
      }
      case CONTEXT_BUDGET_EVALUATED -> {
        closeAssistantLine();
        ContextBudgetEvaluatedPayload payload = (ContextBudgetEvaluatedPayload) event.payload();
        output.println(
            "[context] budget evaluated: "
                + payload.totalTokens()
                + "/"
                + payload.inputBudget()
                + " tokens, included "
                + payload.included().size()
                + ", dropped "
                + payload.dropped().size());
      }
      case CONTEXT_BUDGET_TRIMMED -> {
        closeAssistantLine();
        ContextBudgetTrimmedPayload payload = (ContextBudgetTrimmedPayload) event.payload();
        output.println("[context] budget trimmed: dropped " + String.join(", ", payload.dropped()));
      }
      case FILE_SUMMARY_SAVED -> {
        closeAssistantLine();
        FileSummarySavedPayload payload = (FileSummarySavedPayload) event.payload();
        output.println(
            "[file-summary] saved "
                + displayName(null, payload.path())
                + " ("
                + payload.summaryCharacters()
                + " chars)");
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
      case TOPIC_GENERATION_STARTED -> {
        closeAssistantLine();
        TopicGenerationStartedPayload payload = (TopicGenerationStartedPayload) event.payload();
        output.println("[topic] generation started");
        output.println("        id: " + payload.topicGenerationId());
      }
      case TOPIC_IDLE_TRIGGER_EVALUATED -> {
        closeAssistantLine();
        TopicIdleTriggerEvaluatedPayload payload =
            (TopicIdleTriggerEvaluatedPayload) event.payload();
        output.println("[topic] idle trigger " + (payload.accepted() ? "accepted" : "skipped"));
        output.println("        idle: " + formatDuration(payload.idleDurationMs()));
        output.println("        required: " + formatDuration(payload.requiredIdleMs()));
        if (payload.rejectReason() != null) {
          output.println("        reason: " + payload.rejectReason());
        }
      }
      case TOPIC_CANDIDATES_REFRESHED -> {
        TopicCandidatesRefreshedPayload payload = (TopicCandidatesRefreshedPayload) event.payload();
        if (payload.candidateCount() == 0) return;
        closeAssistantLine();
        output.println("[topic] candidates refreshed");
        output.println("        candidates: " + payload.candidateCount());
        payload.topics().forEach(topic -> output.println("        - " + oneLineSummary(topic)));
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
        output.println(
            "        score: "
                + formatNullableScore(
                    payload.score() == null ? null : payload.score().finalScore()));
        if (payload.score() != null) {
          output.println(
              "        priority: +" + formatNullableScore(payload.score().priorityContribution()));
          output.println(
              "        freshness: +"
                  + formatNullableScore(payload.score().freshnessContribution()));
          output.println(
              "        usefulness: +"
                  + formatNullableScore(payload.score().usefulnessContribution()));
          output.println(
              "        confidence: +"
                  + formatNullableScore(payload.score().confidenceContribution()));
          output.println(
              "        intrusiveness: -"
                  + formatNullableScore(payload.score().intrusivenessPenalty()));
          output.println(
              "        repetition: -" + formatNullableScore(payload.score().repetitionPenalty()));
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
        output.println(
            "[file] modified " + displayName(null, payload.path()) + lineDeltaSuffix(payload));
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
        BackgroundProcessCompletedPayload payload =
            (BackgroundProcessCompletedPayload) event.payload();
        output.println(
            "[process] completed "
                + payload.processId()
                + " exit="
                + payload.exitCode()
                + " ("
                + formatDecimal(payload.elapsedSeconds())
                + " s)");
      }
      case BACKGROUND_PROCESS_FAILED -> {
        closeAssistantLine();
        BackgroundProcessFailedPayload payload = (BackgroundProcessFailedPayload) event.payload();
        output.println(
            "[process] failed " + payload.processId() + ": " + errorMessage(payload.error()));
      }
      case BACKGROUND_PROCESS_KILLED -> {
        closeAssistantLine();
        BackgroundProcessKilledPayload payload = (BackgroundProcessKilledPayload) event.payload();
        output.println(
            "[process] killed "
                + payload.processId()
                + " exit="
                + payload.exitCode()
                + " ("
                + formatDecimal(payload.elapsedSeconds())
                + " s)");
      }
      default -> {}
    }
    output.flush();
  }

  /**
   * 通知に応じて状態や表示を更新するため、直近イベントを受け付ける。
   *
   * <p>Compact restore mode; audit records remain unchanged and can also be fed to onEvent for full
   * replay.
   */
  public synchronized void onRecentEvent(AgentEvent event) {
    if (event.payload() instanceof dev.mikoto2000.rei.event.SubAgentLifecyclePayload child)
      rememberSubAgent(child);
    String detail =
        switch (event.payload()) {
          case dev.mikoto2000.rei.event.SubAgentLifecyclePayload child ->
              child.agentId() + " " + child.status();
          case ToolCompletedPayload tool -> tool.toolName();
          case ToolStartedPayload tool -> tool.toolName();
          case ToolFailedPayload tool -> tool.toolName();
          case WorkingSetItemAddedPayload item -> item.path();
          case WorkingSetItemRemovedPayload item -> item.itemId();
          case dev.mikoto2000.rei.event.UserInterventionPayload input -> input.text();
          case null, default -> "";
        };
    detail = detail == null ? "" : detail.replaceAll("\\s+", " ");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (detail.length() > 100) detail = detail.substring(0, 100) + "…";
    String time =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(event.timestamp());
    output.println(
        "  "
            + time
            + " "
            + (isSubAgentEvent(event) ? subAgentPrefix(event) : "")
            + event.type().value()
            + (detail.isBlank() ? "" : " " + detail));
  }

  /** 終了時に必要な状態を反映し、処理を完了させる。 */
  public synchronized void finish() {
    closeAssistantLine();
    closeThinkingLine();
    output.flush();
  }

  /** 利用者が内容を確認できるよう、バックグラウンド実行を表示する。 */
  public synchronized void renderBackgroundExecution(
      dev.mikoto2000.rei.event.BackgroundExecutionPayload payload, String projectName) {
    finish();
    String state =
        switch (payload.status()) {
          case "RUNNING" -> "started";
          case "COMPLETED" -> "completed";
          case "CANCELLED" -> "cancelled";
          default -> "failed";
        };
    output.println(
        "["
            + payload.executionType().name().toLowerCase(java.util.Locale.ROOT)
            + "."
            + state
            + "] "
            + projectName
            + " ("
            + payload.executionId()
            + ")");
    if (payload.request() != null && !payload.request().isBlank())
      output.println("Source / request: " + payload.request());
    if ("TIMED_OUT".equals(payload.status())) output.println("Status: TIMED_OUT");
    if (payload.output() != null && !payload.output().isBlank()) output.println(payload.output());
    output.flush();
  }

  /** ターミナルでの対話において、話題イベントかどうかを判定する。 */
  private boolean isTopicEvent(AgentEvent event) {
    return event.type().name().startsWith("TOPIC_");
  }

  /** 利用者が内容を確認できるよう、話題要約を表示する。 */
  private void renderTopicSummary(AgentEvent event) {
    if (topicNotificationOptions.verbosity() == TopicNotificationVerbosity.QUIET) return;
    switch (event.type()) {
      case TOPIC_CANDIDATES_REFRESHED -> {
        TopicCandidatesRefreshedPayload payload = (TopicCandidatesRefreshedPayload) event.payload();
        if (payload.candidateCount() == 0) return;
        closeAssistantLine();
        output.println("[topic] candidates refreshed: " + payload.candidateCount());
        payload.topics().forEach(topic -> output.println("        - " + oneLineSummary(topic)));
      }
      case TOPIC_SPOKEN -> {
        if (!allowThrottledTopicSummary(event)) return;
        closeAssistantLine();
        TopicSpokenPayload payload = (TopicSpokenPayload) event.payload();
        output.println(
            "[topic] spoken: "
                + valueOr(payload.candidateId(), "none")
                + " -> "
                + payload.messageId());
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
        TopicIdleTriggerEvaluatedPayload payload =
            (TopicIdleTriggerEvaluatedPayload) event.payload();
        if (payload.accepted() || !topicNotificationOptions.showIdleSkipped()) return;
        if (!allowThrottledTopicSummary(event)) return;
        closeAssistantLine();
        output.println("[topic] idle trigger skipped");
      }
      case TOPIC_GENERATION_FAILED -> {
        closeAssistantLine();
        TopicGenerationFailedPayload payload = (TopicGenerationFailedPayload) event.payload();
        output.println(
            "[topic] generation failed: "
                + payload.stage()
                + " "
                + oneLineSummary(payload.errorSummary()));
      }
      default -> {}
    }
  }

  /** ターミナルでの対話において、許可Throttled話題要約に該当するか判定する。 */
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

  /** ターミナルでの対話で用いるイベント時刻を返す。 */
  private String eventTime(AgentEvent event) {
    return java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(java.time.ZoneId.systemDefault())
        .format(event.timestamp());
  }

  /** ターミナルでの対話に関するメッセージ開始を処理する。 */
  private void messageStarted(MessageStartedPayload payload, AgentEvent event) {
    if ("assistant".equalsIgnoreCase(payload.role())) {
      closeThinkingLine();
      assistantMessageId = payload.messageId();
      output.println("=== answer (" + eventTime(event) + ") ===");
    }
  }

  /** ターミナルでの対話に関する推論開始を処理する。 */
  private void thinkingStarted(ThinkingStartedPayload payload, AgentEvent event) {
    closeAssistantLine();
    thinkingId = payload.thinkingId();
    thinkingLineOpen = false;
    output.println("=== thinking (" + eventTime(event) + ") ===");
  }

  /** ターミナルでの対話に関する推論差分を処理する。 */
  private void thinkingDelta(ThinkingDeltaPayload payload) {
    if (!payload.thinkingId().equals(thinkingId) || payload.delta() == null) return;
    output.print(payload.delta());
    thinkingLineOpen = true;
  }

  /** ターミナルでの対話に関する推論完了を処理する。 */
  private void thinkingCompleted(ThinkingCompletedPayload payload) {
    if (!payload.thinkingId().equals(thinkingId)) return;
    closeThinkingLine();
    thinkingId = null;
    if (assistantMessageId == null) flushLlmCompletions();
  }

  /** ターミナルでの対話に関するメッセージ差分を処理する。 */
  private void messageDelta(MessageDeltaPayload payload) {
    if (!payload.messageId().equals(assistantMessageId) || payload.delta() == null) return;
    if (toolInterruptedMessage) {
      output.println("");
      toolInterruptedMessage = false;
    }
    output.print(payload.delta());
    assistantLineOpen = true;
  }

  /** ターミナルでの対話に関するメッセージ完了を処理する。 */
  private void messageCompleted(MessageCompletedPayload payload) {
    if (!payload.messageId().equals(assistantMessageId)) return;
    closeAssistantLine();
    assistantMessageId = null;
    toolInterruptedMessage = false;
    if (thinkingId == null) flushLlmCompletions();
  }

  /** 本処理に必要な文脈や条件を整えるため、ツールを準備する。 */
  private void beforeTool() {
    if (assistantMessageId != null) toolInterruptedMessage = true;
    closeAssistantLine();
    closeThinkingLine();
    flushLlmCompletions();
  }

  /** ターミナルでの対話に関する保存の確定言語モデルCompletionsを処理する。 */
  private void flushLlmCompletions() {
    if (!flushingNotifications) {
      flushingNotifications = true;
      try {
        var notifications = new java.util.ArrayList<>(pendingNotifications);
        pendingNotifications.clear();
        for (AgentEvent event : notifications) onEvent(event);
      } finally {
        flushingNotifications = false;
      }
    }
    for (AgentEvent event : pendingLlmCompletions) {
      renderLlmCompletion(event);
    }
    pendingLlmCompletions.clear();
  }

  /** 利用者が内容を確認できるよう、モデル応答の完了を表示する。 */
  private void renderLlmCompletion(AgentEvent event) {
    LlmResponseCompletedPayload payload = (LlmResponseCompletedPayload) event.payload();
    String feature =
        llmRequestFeatures.remove(new LlmRequestKey(event.runId(), payload.requestId()));
    output.println(
        "[llm] response received ("
            + (feature == null ? payload.requestId() : feature)
            + ", "
            + payload.durationMs()
            + " ms)");
  }

  /** 使い終わった資源や状態を解放するため、アシスタント行の利用を終える。 */
  private void closeAssistantLine() {
    if (assistantLineOpen) {
      output.println("");
      assistantLineOpen = false;
    }
  }

  /** 使い終わった資源や状態を解放するため、推論行の利用を終える。 */
  private void closeThinkingLine() {
    if (thinkingLineOpen) {
      output.println("");
      thinkingLineOpen = false;
    }
  }

  /** ターミナルでの対話で用いるエラーメッセージを返す。 */
  private String errorMessage(dev.mikoto2000.rei.event.ErrorInformation error) {
    if (error == null) return dev.mikoto2000.rei.event.FailureMessages.text(null);
    String reason = error.message();
    if (reason == null || reason.isBlank()) reason = error.errorType();
    if (error.code() != null && !error.code().isBlank())
      reason = "[" + error.code() + "] " + reason;
    return dev.mikoto2000.rei.event.FailureMessages.text(reason);
  }

  /** 内容を表示・参照しやすくするため、秒数を整形する。 */
  private String formatSeconds(long durationMillis) {
    return formatDecimal(durationMillis / 1_000.0d);
  }

  /** 内容を表示・参照しやすくするため、継続時間を整形する。 */
  private String formatDuration(long durationMillis) {
    return durationMillis < 1_000L
        ? durationMillis + "ms"
        : String.format(java.util.Locale.ROOT, "%.2fs", durationMillis / 1_000.0d);
  }

  /** ターミナルでの対話で用いる対象の選択条件接尾辞を返す。 */
  private String selectorSuffix(Long durationMillis) {
    return durationMillis == null ? "" : ", selector " + formatDuration(durationMillis);
  }

  /** 内容を表示・参照しやすくするため、小数を整形する。 */
  private String formatDecimal(double value) {
    return String.format(java.util.Locale.ROOT, "%.1f", value);
  }

  /** 内容を表示・参照しやすくするため、Nullable評価値を整形する。 */
  private String formatNullableScore(Double value) {
    return value == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  /** ターミナルでの対話で用いる小文字を返す。 */
  private String lower(String value) {
    return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
  }

  /** ターミナルでの対話で用いる単一行要約を返す。 */
  private String oneLineSummary(String value) {
    if (value == null || value.isBlank()) return "";
    StringBuilder sanitized = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      sanitized.append(Character.isISOControl(character) ? ' ' : character);
    }
    return sanitized.toString().replaceAll("\\s+", " ").trim();
  }

  /** ターミナルでの対話で用いる表示名前を返す。 */
  private String displayName(String identifier, String path) {
    String value = identifier == null || identifier.isBlank() ? path : identifier;
    if (value == null || value.isBlank()) return "item";
    String normalized = value.replace('\\', '/');
    int separator = normalized.lastIndexOf('/');
    return separator < 0 ? normalized : normalized.substring(separator + 1);
  }

  /** ターミナルでの対話で用いる理由接尾辞を返す。 */
  private String reasonSuffix(String reason) {
    String safe = oneLineSummary(reason);
    if (safe.isEmpty()) return "";
    int maxLength = 120;
    if (safe.length() > maxLength) safe = safe.substring(0, maxLength - 1) + "…";
    return " (" + safe + ")";
  }

  /** 後続の処理で扱える量に制限するため、要約を上限内に収める。 */
  private String boundedSummary(String value, int maxLength) {
    String safe = oneLineSummary(value);
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
  }

  /** ターミナルでの対話で用いる値またはを返す。 */
  private String valueOr(String preferred, String fallback) {
    return preferred == null ? fallback : preferred;
  }

  /** ターミナルでの対話で用いる値またはを返す。 */
  private int valueOr(Integer preferred, int fallback) {
    return preferred == null ? fallback : preferred;
  }

  /** ターミナルでの対話で用いる行差分接尾辞を返す。 */
  private String lineDeltaSuffix(FileModifiedPayload payload) {
    if (payload.addedLines() == null && payload.removedLines() == null) return "";
    return " (+"
        + valueOr(payload.addedLines(), 0)
        + "/-"
        + valueOr(payload.removedLines(), 0)
        + ")";
  }

  /** ターミナルでの対話における話題通知説明量の選択肢。 */
  public enum TopicNotificationVerbosity {
    /** 案内を控えめにする通知設定。 */
    QUIET,
    /** 要約。 */
    SUMMARY,
    /** 詳細な形式。 */
    VERBOSE
  }

  /**
   * ターミナルでの対話で受け渡す話題通知選択可能な設定を表す。
   *
   * @param verbosity 説明量
   * @param minimumInterval 最小実行間隔
   * @param showIdleSkipped 表示操作がない状態対象外
   */
  public record TopicNotificationOptions(
      TopicNotificationVerbosity verbosity, Duration minimumInterval, boolean showIdleSkipped) {

    /** ターミナルでの対話で用いる要約を返す。 */
    public static TopicNotificationOptions summary() {
      return new TopicNotificationOptions(
          TopicNotificationVerbosity.SUMMARY, Duration.ofMinutes(30), false);
    }

    /** ターミナルでの対話で用いる詳細な形式を返す。 */
    public static TopicNotificationOptions verbose() {
      return new TopicNotificationOptions(TopicNotificationVerbosity.VERBOSE, Duration.ZERO, true);
    }

    /** ターミナルでの対話で利用する情報を、受け取った形式から変換する。 */
    public static TopicNotificationOptions from(Environment environment) {
      if (environment == null) return summary();
      String verbosityValue =
          environment.getProperty("rei.topic-generator.shell-notification.verbosity", "summary");
      TopicNotificationVerbosity verbosity = parseVerbosity(verbosityValue);
      Duration minimumInterval =
          environment.getProperty(
              "rei.topic-generator.shell-notification.minimum-interval",
              Duration.class,
              Duration.ofMinutes(30));
      boolean showIdleSkipped =
          environment.getProperty(
              "rei.topic-generator.shell-notification.show-idle-skipped",
              Boolean.class,
              Boolean.FALSE);
      return new TopicNotificationOptions(verbosity, minimumInterval, showIdleSkipped);
    }

    /** 入力を機能内で扱えるよう、説明量を解釈する。 */
    private static TopicNotificationVerbosity parseVerbosity(String value) {
      if (value == null || value.isBlank()) return TopicNotificationVerbosity.SUMMARY;
      try {
        return TopicNotificationVerbosity.valueOf(
            value.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        return TopicNotificationVerbosity.SUMMARY;
      }
    }
  }
}
