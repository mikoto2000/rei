package dev.mikoto2000.rei.core.stagnation;

import dev.mikoto2000.rei.llm.OutputLimitDetector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 実行の停滞への対処に必要な停滞応答生成に使うモデルを扱う。
 *
 * <p>Explicit streaming LLM -> tool-result loop. Existing client advisors still own prompt/memory
 * handling.
 */
public class StagnationChatModel implements ChatModel {
  /** 委譲先。 */
  private final ChatModel delegate;

  /** 文脈の組み立て。 */
  private final dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler;

  /** ツール一覧。 */
  private final dev.mikoto2000.rei.core.chat.ToolLoopSupport tools =
      new dev.mikoto2000.rei.core.chat.ToolLoopSupport();

  /** 実行の停滞への対処を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public StagnationChatModel(ChatModel delegate) {
    this(delegate, null);
  }

  /** 指定された値や利用先を使って、StagnationChatModelを初期化する。 */
  public StagnationChatModel(
      ChatModel delegate, dev.mikoto2000.rei.core.contextbudget.ContextAssembler assembler) {
    this.delegate = delegate;
    this.assembler = assembler;
  }

  /** 実行の停滞への対処で参照する選択可能な設定を返す。 */
  @Override
  public ChatOptions getOptions() {
    return delegate.getOptions();
  }

  /** 実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public ChatResponse call(Prompt prompt) {
    if (context(prompt) == null) return delegate.call(prompt);
    AtomicReference<ChatResponse> result = new AtomicReference<>();
    new MessageAggregator().aggregate(stream(prompt), result::set).blockLast();
    return result.get();
  }

  /** 実行の停滞への対処で用いる逐次データを返す。 */
  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    RunExecutionContext context = context(prompt);
    if (context == null) return delegate.stream(prompt);
    return Flux.defer(
            () -> {
              var prepared = prepare(prompt, context);
              var pending = context.takePendingApprovalTools();
              if (pending == null)
                return iteration(prepared, context, true, context.progressVersion());
              return Mono.fromCallable(
                      () -> {
                        context.checkActive();
                        context.beginIteration();
                        try {
                          context.planTools(
                              pending
                                  .calls()
                                  .subList(pending.completed().size(), pending.calls().size()));
                          var result = tools.executePending(prepared, pending, context);
                          context.preserveToolResults(result.conversationHistory());
                          context.suspendIfWaiting(result.conversationHistory());
                          return result;
                        } finally {
                          context.endIteration();
                        }
                      })
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMapMany(
                      result -> {
                        if (result.returnDirect())
                          return Flux.just(
                              new ChatResponse(ToolExecutionResult.buildGenerations(result)));
                        return iteration(
                            new Prompt(
                                withoutResumeWorkReminder(result.conversationHistory()),
                                prepared.getOptions()),
                            context,
                            false,
                            context.progressVersion());
                      });
            })
        .doOnError(
            error -> {
              if (assembler != null)
                assembler.finishReviewRun(
                    context.runContext(),
                    error instanceof java.util.concurrent.CancellationException,
                    false,
                    null);
            })
        .doOnCancel(
            () -> {
              if (assembler != null)
                assembler.finishReviewRun(context.runContext(), true, false, null);
            });
  }

  /** 実行の停滞への対処で用いる繰り返しを返す。 */
  private Flux<ChatResponse> iteration(
      Prompt prompt, RunExecutionContext context, boolean prepaid, long progressAtLimit) {
    return Flux.defer(
        () -> {
          context.checkActive();
          context.checkModelTokenBudget();
          if (!prepaid) context.consumeNextLlmCall();
          Prompt actionPrompt = resumeRecoveryPrompt(prompt, context);
          String feedback = context.progressFeedback() + context.resumeBatchFeedback();
          if (context.isResumedContext() && hasReviewProtocol(prompt)) {
            feedback +=
                " For review work, persist each assessed source range with beginReview, readReviewFile"
                    + " and a delivered-page assessment. Prefer readReviewPages and submitReviewPages for bounded batches; otherwise submitReviewPage: copy only"
                    + " the delivered path/offset/nextOffset and cite unique exact evidence quotes; the server"
                    + " supplies hashes and source coordinates. The legacy submitReviewResult requires the"
                    + " exact unchanged Range object. A matching quote proves presence, not that a defect exists."
                    + " Corroborate cross-file claims against referenced source, navigation/configuration and rendered"
                    + " output before repairs. Use dismissReviewFinding, when available, for disproved candidates"
                    + " with a reason citing the counterevidence. If no reviewJobId exists, create one with beginReview"
                    + " for the explicit authorized targets and criteria; the missing ID is not a blocker."
                    + " Use reviewPending to select unfinished ranges and reviewFindings for preserved candidates."
                    + " Do not declare a previously submitted assessment unreviewed merely because its source"
                    + " left the current prompt. Changed source versions require restartReview."
                    + " Reports supplement the ledger; repeatedly appending a list of unread files does not"
                    + " complete their review. An exact registered review page can be acquired at the unit"
                    + " boundary to prepare its structured assessment; submit that delivered range before"
                    + " further bulk reading. If that page leaves the prompt, redeliver it with the same"
                    + " job/path/version/offset/tokenBudget before assessing; never invent an assessment from"
                    + " ledger counts. Batch a work unit into one tool-call response: submit all assessed"
                    + " delivered pages first, then request their known nextOffset pages in the same response."
                    + " Assess only text already delivered. This confirms outcomes before acquiring new source. Follow the original scope"
                    + " through all remaining targets.";
            if (assembler != null) feedback += assembler.reviewContinuation(context.runContext());
          }
          if (actionPrompt != prompt && !context.needsResumeContentWork())
            feedback +=
                " Broad discovery tools are temporarily unavailable during this depleted resume. "
                    + "Use the handed-off historical inventory, fetched coverage and read excerpts to select an unfinished file or chapter. Read named missing content across pages, then save one consolidated result for that work unit. "
                    + "The inventory is not proof that all files exist unchanged or that review is complete. "
                    + "Normal discovery returns after verified work or step advancement; new coverage alone does not end this recovery. Budgets are not replenished.";
          if (actionPrompt != prompt && context.needsResumeContentWork())
            feedback +=
                " Content acquisition is temporarily unavailable at the resumed work-unit boundary. "
                    + "Use only the text actually visible in this context to record an authorized partial assessment for this unit. "
                    + "Cite the delivered path/range and evidence, distinguish assessed content from unread or omitted content, and state exact unresolved gaps. "
                    + "For review, use submitReviewResult for a delivered review range or update the authorized review artifact incrementally. "
                    + "Do not invent findings, evidence, authorization or completion. "
                    + "If an assessment is not supportable, explain the specific blockage instead of fabricating work. "
                    + "Actual work advancement restores acquisition for the next work unit; failed writes, annotations and repeated text alone do not.";
          var invocationMessages = new ArrayList<Message>(actionPrompt.getInstructions());
          if (!feedback.isEmpty())
            invocationMessages.addFirst(
                SystemMessage.builder()
                    .text(feedback)
                    .metadata(java.util.Map.of("rei.progressFeedback", true))
                    .build());
          Prompt invocationPrompt = new Prompt(invocationMessages, actionPrompt.getOptions());
          context.beginIteration();
          AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
          AtomicReference<Prompt> sent = new AtomicReference<>();
          var invoked = new java.util.concurrent.atomic.AtomicBoolean();
          var reported = new java.util.concurrent.atomic.AtomicBoolean();
          Flux<ChatResponse> requests =
              assembler == null || context.runContext() == null
                  ? Flux.defer(
                      () -> {
                        context.checkModelTokenBudget();
                        invoked.set(true);
                        return delegate.stream(invocationPrompt);
                      })
                  : Mono.fromCallable(
                          () -> {
                            try (var scope =
                                dev.mikoto2000.rei.core.chat.AgentRunScope.open(
                                    context.runContext())) {
                              try {
                                return assembler.assemble(
                                    invocationPrompt,
                                    context.runContext().conversationId(),
                                    context.runContext().runId(),
                                    context::checkActive);
                              } catch (java.util.concurrent.CancellationException cancelled) {
                                // Disposal may interrupt the summary worker after the downstream
                                // subscriber has gone away.
                                if (context.isCancelled()) return null;
                                throw cancelled;
                              }
                            }
                          })
                      .subscribeOn(Schedulers.boundedElastic())
                      .flatMapMany(
                          projected -> {
                            context.checkActive();
                            context.checkModelTokenBudget();
                            invoked.set(true);
                            sent.set(projected);
                            return delegate.stream(projected);
                          });
          Flux<ChatResponse> response = new MessageAggregator().aggregate(requests, aggregate::set);
          return response
              .concatWith(
                  Flux.defer(
                      () -> {
                        ChatResponse result = aggregate.get();
                        var generation = result == null ? null : result.getResult();
                        if (generation == null) {
                          reported.set(true);
                          context.recordTotalTokens(null);
                          context.endIteration();
                          if (context.hasPendingResumeContentWork())
                            return continuePendingContentWork(
                                prompt, null, context, progressAtLimit);
                          return finishOrContinueReview(prompt, null, context, progressAtLimit);
                        }
                        context.checkActive();
                        if (assembler != null && sent.get() != null)
                          assembler.confirmedDelivery(sent.get(), context.runContext());
                        var usage = result.getMetadata().getUsage();
                        if (usage != null
                            && usage.getPromptTokens() != null
                            && usage.getPromptTokens() > 0)
                          dev.mikoto2000.rei.tooldelivery.ReadingMetrics.add(
                              context.runContext(), "providerInputTokens", usage.getPromptTokens());
                        else
                          dev.mikoto2000.rei.tooldelivery.ReadingMetrics.add(
                              context.runContext(), "providerInputTokensUnavailable", 1);
                        reported.set(true);
                        context.recordTotalTokens(usage == null ? null : usage.getTotalTokens());
                        if (usage != null && usage.getCompletionTokens() != null) {
                          context.recordCompletionTokens(usage.getCompletionTokens());
                        }
                        if (result.hasToolCalls()
                            && !OutputLimitDetector.isOutputLimitReached(result)) {
                          return Mono.fromCallable(
                                  () -> {
                                    context.checkActive();
                                    try {
                                      context.planTools(generation.getOutput().getToolCalls());
                                      var toolResult =
                                          tools.executeResumable(actionPrompt, result, context);
                                      var history =
                                          withoutResumeWorkReminder(
                                              toolResult.conversationHistory());
                                      if (assembler != null && context.runContext() != null)
                                        assembler.preserveToolResults(
                                            history,
                                            context.runContext().conversationId(),
                                            context.runContext().runId());
                                      context.preserveToolResults(history);
                                      context.suspendIfWaiting(history);
                                      return toolResult;
                                    } finally {
                                      context.endIteration();
                                    }
                                  })
                              .subscribeOn(Schedulers.boundedElastic())
                              .flatMapMany(
                                  toolResult -> {
                                    if (toolResult.returnDirect())
                                      return Flux.just(
                                          new ChatResponse(
                                              ToolExecutionResult.buildGenerations(toolResult)));
                                    List<Message> messages =
                                        new ArrayList<>(
                                            withoutResumeWorkReminder(
                                                toolResult.conversationHistory()));
                                    messages.addAll(context.applyInterventions());
                                    String notice = context.requestReplan();
                                    if (notice != null) messages.add(new UserMessage(notice));
                                    return iteration(
                                        new Prompt(messages, prompt.getOptions()),
                                        context,
                                        false,
                                        progressAtLimit);
                                  });
                        }
                        context.endIteration();
                        if (!OutputLimitDetector.isOutputLimitReached(result)) {
                          var guidance = context.applyInterventions();
                          if (guidance.isEmpty()) {
                            if (context.hasPendingResumeContentWork())
                              return continuePendingContentWork(
                                  prompt, generation.getOutput(), context, progressAtLimit);
                            return finishOrContinueReview(
                                prompt, generation.getOutput(), context, progressAtLimit);
                          }
                          List<Message> messages = new ArrayList<>(prompt.getInstructions());
                          messages.add(generation.getOutput());
                          messages.addAll(guidance);
                          return iteration(
                              new Prompt(messages, prompt.getOptions()),
                              context,
                              false,
                              progressAtLimit);
                        }
                        String notice = context.requestReplan();
                        if (notice != null || context.progressVersion() > progressAtLimit) {
                          List<Message> messages = new ArrayList<>(prompt.getInstructions());
                          // A length-truncated tool call has no complete arguments and must never
                          // be replayed/executed.
                          messages.add(
                              new AssistantMessage(
                                  Objects.toString(generation.getOutput().getText(), "")));
                          messages.add(
                              new UserMessage(
                                  notice != null
                                      ? notice
                                      : "Continue the current goal from the verified progress. Do not repeat completed actions. Keep the next output concise."));
                          return iteration(
                              new Prompt(messages, prompt.getOptions()),
                              context,
                              false,
                              context.progressVersion());
                        }
                        return Flux
                            .empty(); // Outer output-limit splitter handles a goal that made no
                        // progress.
                      }))
              .doOnError(
                  error -> {
                    context.endIteration();
                    if (invoked.get() && !reported.getAndSet(true)) context.recordTotalTokens(null);
                  })
              .doOnCancel(
                  () -> {
                    context.endIteration();
                    if (invoked.get() && !reported.getAndSet(true)) {
                      try {
                        context.recordTotalTokens(null);
                      } catch (RuntimeException stopped) {
                        /* Already cancelled or blocked by unknown usage. */
                      }
                    }
                  });
        });
  }

  /** Use the registered range protocol only when its callbacks are actually available. */
  private boolean hasReviewProtocol(Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) return false;
    var callbacks = options.getToolCallbacks();
    if (callbacks == null) return false;
    var names = callbacks.stream().map(t -> t.getToolDefinition().name()).toList();
    return names.containsAll(List.of("beginReview", "readReviewFile"))
        && (names.contains("submitReviewPage") || names.contains("submitReviewResult"));
  }

  private Flux<ChatResponse> continueIncompleteReview(
      Prompt prompt, AssistantMessage reply, RunExecutionContext context, long progressAtLimit) {
    String notice = context.requestReplan();
    var messages = new ArrayList<Message>(withoutResumeWorkReminder(prompt.getInstructions()));
    if (reply != null) messages.add(reply);
    messages.add(
        SystemMessage.builder()
            .text(
                "The persisted review ledger still has unfinished or stale ranges. This response cannot"
                    + " complete the review. Continue with reviewPending/readReviewFile/submitReviewResult;"
                    + " preserve already verified assessments. Do not restart all files or append another"
                    + " unread-file report in place of assessing a delivered range."
                    + assembler.reviewContinuation(context.runContext())
                    + (notice == null ? "" : " " + notice))
            .metadata(java.util.Map.of("rei.resumeWorkReminder", true))
            .build());
    return iteration(new Prompt(messages, prompt.getOptions()), context, false, progressAtLimit);
  }

  private Flux<ChatResponse> finishOrContinueReview(
      Prompt prompt, AssistantMessage reply, RunExecutionContext context, long progressAtLimit) {
    if (assembler != null) {
      try {
        assembler.requireReviewed(context.runContext());
      } catch (ExecutionStoppedException incomplete) {
        if (!context.isResumedContext()
            || incomplete.reason() != ExecutionStoppedException.Reason.REVIEW_PARTIAL
            || !hasReviewProtocol(prompt)) throw incomplete;
        return continueIncompleteReview(prompt, reply, context, progressAtLimit);
      }
    }
    return Flux.empty();
  }

  /** A text-only or empty response cannot finish an outstanding resumed content assessment. */
  private Flux<ChatResponse> continuePendingContentWork(
      Prompt prompt, AssistantMessage reply, RunExecutionContext context, long progressAtLimit) {
    context.requireResumeBatchCommit();
    // The response already consumed an ordinary iteration. Enforce the same shared limits as
    // tool iterations; do not replenish budgets or treat continuation wording as progress.
    String notice = context.requestReplan();
    List<Message> messages = new ArrayList<>(withoutResumeWorkReminder(prompt.getInstructions()));
    if (reply != null) messages.add(reply);
    messages.add(
        SystemMessage.builder()
            .text(
                "No verified work followed the delivered content. A text-only promise to continue is not execution or completion. "
                    + "Use an available authorized tool now to record a consolidated partial assessment for the work unit from the visible evidence, with exact gaps. "
                    + "Do not announce another read while content acquisition is withheld. "
                    + "Do not fabricate findings, permission or completion. If blocked, record the specific blocker through the existing task controls. "
                    + "The existing no-progress and shared call limits still apply."
                    + (notice == null ? "" : " " + notice))
            .metadata(java.util.Map.of("rei.resumeWorkReminder", true))
            .build());
    return iteration(new Prompt(messages, prompt.getOptions()), context, false, progressAtLimit);
  }

  /** The reminder describes one decision, not a permanent restriction after verified work. */
  private List<Message> withoutResumeWorkReminder(List<Message> messages) {
    return messages.stream()
        .filter(
            m ->
                !(m instanceof SystemMessage
                    && (Boolean.TRUE.equals(m.getMetadata().get("rei.resumeWorkReminder"))
                        || Boolean.TRUE.equals(m.getMetadata().get("rei.progressFeedback")))))
        .toList();
  }

  /** Limit only the recovery choice, keeping the complete callbacks for the next iteration. */
  private Prompt resumeRecoveryPrompt(Prompt prompt, RunExecutionContext context) {
    var owner = context.runContext();
    if (!context.needsResumeWorkRecovery() || owner == null) return prompt;
    // The advisor prepends the current handoff; saved continuation messages follow it.
    boolean inventoryDelivered =
        prompt.getInstructions().stream()
            .filter(
                m ->
                    m instanceof SystemMessage
                        && Boolean.TRUE.equals(m.getMetadata().get("rei.resumeContext"))
                        && owner.runId().equals(m.getMetadata().get("rei.resumeRunId")))
            .findFirst()
            .map(m -> Boolean.TRUE.equals(m.getMetadata().get("rei.knownFileInventory")))
            .orElse(false);
    // Delivered source content establishes the assessment boundary independently of inventory.
    if (!inventoryDelivered && !context.needsResumeContentWork()) return prompt;
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) return prompt;
    var available = options.getToolCallbacks();
    if (available == null) return prompt;
    var excluded =
        java.util.Set.of(
            "listFile", "findFile", "countFileLines", "checkpointShow", "checkpointInspect");
    var callbacks =
        available.stream()
            .filter(t -> !excluded.contains(t.getToolDefinition().name()))
            .filter(t -> !context.resumeToolHidden(t.getToolDefinition().name()))
            .toList();
    // The model must return a real function call at the outcome boundary. Merely asking
    // for one in prose permits a text-only "I will save it" response, which consumes the
    // last bounded commit attempt without executing anything.
    if (context.needsResumeContentWork()
        && !callbacks.isEmpty()
        && options instanceof org.springframework.ai.openai.OpenAiChatOptions openAi
        && (openAi.getToolChoice() == null || "auto".equals(openAi.getToolChoice())))
      return new Prompt(
          prompt.getInstructions(),
          openAi.mutate().toolCallbacks(callbacks).toolChoice("required").build());
    if (callbacks.size() == available.size()) return prompt;
    return new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(callbacks).build());
  }

  /** 実行の停滞への対処で用いる準備を返す。 */
  private Prompt prepare(Prompt prompt, RunExecutionContext context) {
    ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoRawTools(options);
    var toolContext = new HashMap<String, Object>();
    if (options.getToolContext() != null) toolContext.putAll(options.getToolContext());
    toolContext.put("rei.contextSequenceOffset", context.nextContextSegment());
    List<ToolCallback> callbacks = new ArrayList<>();
    if (options.getToolCallbacks() != null)
      for (ToolCallback callback : options.getToolCallbacks())
        callbacks.add(observe(callback, context));
    var resumed = context.takeResumeMessages();
    var instructions = new ArrayList<Message>();
    if (resumed.isEmpty()) instructions.addAll(prompt.getInstructions());
    else {
      prompt.getInstructions().stream()
          .filter(m -> m instanceof SystemMessage)
          .forEach(instructions::add);
      instructions.addAll(resumed);
    }
    return new Prompt(
        withoutResumeWorkReminder(instructions),
        options
            .mutate()
            .toolContext(toolContext)
            .toolCallbacks(dev.mikoto2000.rei.llm.StableToolDefinitions.callbacks(callbacks))
            .build());
  }

  /** 状況を判断する根拠を得るため、停滞応答生成に使うモデルを観測する。 */
  private ToolCallback observe(ToolCallback delegateTool, RunExecutionContext context) {
    return dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.preservePolicy(
        delegateTool,
        new ToolCallback() {
          /** 実行の停滞への対処で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return delegateTool.getToolDefinition();
          }

          /** 実行の停滞への対処で参照するツール付随情報を返す。 */
          public ToolMetadata getToolMetadata() {
            return delegateTool.getToolMetadata();
          }

          /** 実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            return call(input, null);
          }

          /** 実行の停滞への対処の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input, ToolContext toolContext) {
            try (var scope =
                dev.mikoto2000.rei.core.chat.AgentRunScope.open(context.runContext())) {
              context.checkActive();
              String name = getToolDefinition().name();
              // A model may have queued several reads in one response. Recheck at execution
              // time so the work-unit limit also applies between callbacks, before side effects.
              if (context.resumeAcquisitionBlocked(name, input)) {
                // Nothing ran: this host boundary is not a failed I/O attempt. Preserve the
                // result in normal tool history without consuming the unit's outcome attempt.
                return "{\"success\":false,\"error\":\"Resume work unit requires a consolidated outcome before more content acquisition\"}";
              }
              // These two domain callbacks authorize the complete persisted specification, not raw
              // tool JSON.
              // Preparation never starts a process; execution checks Policy/approval on the server
              // before claim.
              if (!(delegateTool
                          instanceof
                          dev.mikoto2000.rei.externalagent.ExternalAgentToolCallback domain
                      && domain.authorizesSpecification())
                  && !(delegateTool instanceof dev.mikoto2000.rei.slack.SlackAgentToolCallback slack
                      && slack.authorizesSpecification())
                  && !(delegateTool instanceof dev.mikoto2000.rei.dot.DotToolCallback dot
                      && dot.authorizesSpecification())) context.checkToolPermission(name, input);
              if (context.externalDelegationUsed()
                  && !(delegateTool
                          instanceof
                          dev.mikoto2000.rei.externalagent.ExternalAgentToolCallback domain
                      && domain.availableAfterDelegation()))
                return "External review is complete or already attempted. Evaluate the supplied result independently and answer; no further tool execution or automatic fixes are allowed in this review run.";
              var before = context.evaluator().beforeTool(name, input);
              boolean decorated =
                  delegateTool instanceof dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
              String actualId = context.claimToolId(name, input);
              var capturedToolContext = new HashMap<String, Object>();
              if (toolContext != null) capturedToolContext.putAll(toolContext.getContext());
              capturedToolContext.put(RunExecutionContext.KEY, context);
              capturedToolContext.put("toolCallId", actualId);
              var invocationContext = new ToolContext(capturedToolContext);
              String durableCall =
                  decorated ? null : context.beginDurableTool(actualId, name, input);
              String result;
              try {
                context.checkActive();
                result = delegateTool.call(input, invocationContext);
              } catch (RuntimeException error) {
                if (!decorated) context.failDurableTool(durableCall, name, error);
                context.recordFailure(name, input, error);
                var rejected =
                    dev.mikoto2000.rei.toolcontract.ToolResultRejectedException.find(error);
                if (rejected instanceof dev.mikoto2000.rei.toolcontract.ToolReadRejectedException read
                    && java.util.Set.of("readReviewPages", "readRawToolResult").contains(name))
                  return context.toolReadCorrection(read.code(), read.getMessage());
                if (rejected != null
                    && java.util.Set.of(
                            "submitReviewResult",
                            "submitReviewPage",
                            "submitReviewPages",
                            "submitReviewWorkItem",
                            "dismissReviewFinding",
                            "restartReview")
                        .contains(name))
                  return context.reviewSubmissionCorrection(rejected.getMessage());
                throw error;
              }
              if (!decorated) context.completeDurableTool(durableCall, name, result);
              context.recordTool(name, input, result, before);
              return result;
            }
          }
        });
  }

  /** 実行の停滞への対処で用いる文脈を返す。 */
  private RunExecutionContext context(Prompt prompt) {
    if (prompt.getOptions() instanceof ToolCallingChatOptions options
        && options.getToolContext() != null
        && options.getToolContext().get(RunExecutionContext.KEY)
            instanceof RunExecutionContext context) return context;
    return null;
  }
}
