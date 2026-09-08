package dev.mikoto2000.rei.core.stagnation;

import java.util.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import static dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.*;

/** One user run, explicitly passed in ToolContext across reactive scheduler boundaries. */
public class RunExecutionContext {
  public static final String KEY = RunExecutionContext.class.getName();
  private final String runId;
  private final OutputLimitRunBudget budget;
  private final StagnationDetector detector = new StagnationDetector();
  private final ProgressEvaluator evaluator;
  private final AgentEventFactory factory;
  private final AgentEventPublisher publisher;
  private final List<ProgressEvidence> pending = new ArrayList<>();
  private final Deque<String> recentActions = new ArrayDeque<>();
  private final Set<String> completedSubgoals = new HashSet<>();
  private String lastError = "none";
  private long progressVersion;
  private long completionTokens;
  private boolean closed;
  private boolean iterationOpen;

  public RunExecutionContext(String runId, OutputLimitRunBudget budget, ProgressEvaluator evaluator,
      AgentEventFactory factory, AgentEventPublisher publisher) {
    this.runId = runId; this.budget = budget; this.evaluator = evaluator;
    this.factory = factory; this.publisher = publisher;
  }

  public StagnationDetector detector() { return detector; }
  public ProgressEvaluator evaluator() { return evaluator; }
  public long progressVersion() { return progressVersion; }
  public synchronized long completionTokens() { return completionTokens; }
  public synchronized void recordCompletionTokens(long tokens) { completionTokens += Math.max(0, tokens); }
  /** Called only by the executor after a planned subgoal returns SUCCESS, not on arbitrary tool success. */
  public synchronized void completeSubgoal(String goal) {
    if (closed || !completedSubgoals.add(goal)) return;
    boolean recovering = detector.replanCount() > 0 || detector.isReplanRequested();
    var evidence = new ProgressEvidence(ProgressEvent.SUBGOAL_COMPLETED,
        "Planned subgoal completed", ProgressEvaluator.actionKey("subgoal", goal));
    detector.recordProgress(evidence.kind());
    progressVersion++;
    emit(AgentEventType.PROGRESS_DETECTED, evidence, "subgoal_completed");
    if (recovering) emit(AgentEventType.STAGNATION_RECOVERED, evidence, "progress_resumed");
  }
  public synchronized void close() { closed = true; pending.clear(); }
  public synchronized void checkActive() {
    if (closed || Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
  }
  public synchronized void beginIteration() { checkActive(); pending.clear(); iterationOpen = true; }
  public synchronized void recordTool(String name, String arguments, String result, ProgressEvaluator.Snapshot before) {
    if (closed) return;
    String action = ProgressEvaluator.actionKey(name, arguments);
    detector.recordToolCall(name, action);
    recentActions.addLast(action);
    if (recentActions.size() > 6) recentActions.removeFirst();
    pending.addAll(evaluator.afterTool(name, arguments, result, before));
  }
  public synchronized void recordFailure(String name, String arguments, RuntimeException error) {
    if (closed) return;
    evaluator.recordFailure(name, arguments);
    lastError = name + ":" + error.getClass().getSimpleName();
    detector.recordFailure(error.getClass().getSimpleName(), name);
  }
  public synchronized void endIteration() {
    if (closed || !iterationOpen) return;
    iterationOpen = false;
    boolean recovering = detector.replanCount() > 0 || detector.isReplanRequested();
    detector.recordIteration(!pending.isEmpty());
    for (ProgressEvidence evidence : pending) {
      detector.recordProgress(evidence.kind());
      progressVersion++;
      emit(AgentEventType.PROGRESS_DETECTED, evidence, "meaningful_progress");
    }
    if (!pending.isEmpty() && recovering) emit(AgentEventType.STAGNATION_RECOVERED, pending.getFirst(), "progress_resumed");
    if (pending.isEmpty()) {
      emit(AgentEventType.STAGNATION_UPDATED, null, "no_progress");
      if (detector.isStagnant()) emit(AgentEventType.STAGNATION_DETECTED, null, "threshold_reached");
    }
    pending.clear();
  }
  public synchronized void consumeNextLlmCall() {
    checkActive();
    if (!budget.tryConsumeLlmCall()) throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
  }
  public synchronized String requestReplan() {
    if (!detector.isReplanRequested()) return null;
    if (detector.isMaxReplanReached()) {
      emit(AgentEventType.STAGNATION_STOPPED, null, "STAGNATED");
      throw new ExecutionStoppedException(STAGNATED);
    }
    if (!budget.hasRemainingLlmCalls()) throw new ExecutionStoppedException(LLM_CALL_BUDGET_EXCEEDED);
    if (!budget.tryConsumeReplan()) throw new ExecutionStoppedException(REPLAN_BUDGET_EXCEEDED);
    String notice = new StagnationAdvisor(detector).replanNotice(
        String.join(", ", recentActions), lastError);
    detector.recordReplan();
    emit(AgentEventType.STAGNATION_REPLAN_REQUESTED, null, "stagnation");
    return notice;
  }
  private void emit(AgentEventType type, ProgressEvidence evidence, String reason) {
    publisher.publish(factory.executionProgress(type, runId, new ExecutionProgressPayload(evidence,
        detector.stagnationCount(), detector.threshold(), detector.replanCount(), detector.maxReplans(), reason)));
  }
}
