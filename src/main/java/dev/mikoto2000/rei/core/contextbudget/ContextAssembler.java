package dev.mikoto2000.rei.core.contextbudget;

import java.time.Instant;
import java.util.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.event.*;

/** The final request projection. Never mutates the loop's raw prompt, working state or permanent history. */
public class ContextAssembler {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextAssembler.class);
  private final ContextCompressionProperties properties;
  private final TokenEstimator estimator;
  private final ConversationSummaryRepository summaries;
  private final ToolResultCompressor tools;
  private final ConversationCompressor compressor;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  private java.util.function.Supplier<String> workingSet;
  public void setWorkingSet(java.util.function.Supplier<String> workingSet) { this.workingSet = workingSet; }
  public void preserveToolResults(List<Message> messages, String conversation, String run) {
    if (!messages.isEmpty()) tools.preserve(messages.getLast(), conversation, run);
  }
  private record Entry(long sequence, Message message) { }
  public ContextAssembler(ContextCompressionProperties properties, TokenEstimator estimator,
      ConversationSummaryRepository summaries, ToolResultCompressor tools, ConversationCompressor compressor,
      AgentEventFactory events, AgentEventPublisher publisher) {
    this.properties = properties; this.estimator = estimator; this.summaries = summaries;
    this.tools = tools; this.compressor = compressor; this.events = events; this.publisher = publisher;
  }
  public Prompt assemble(Prompt raw, String conversation, String run, Runnable checkActive) {
    if (!properties.isEnabled()) return raw;
    var lock = summaries.lock(conversation);
    try {
      lock.lockInterruptibly();
      try { return assembleLocked(raw, conversation, run, checkActive); }
      finally { lock.unlock(); }
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
  }
  private Prompt assembleLocked(Prompt raw, String conversation, String run, Runnable check) {
    check.run();
    int hard = hardLimit(raw);
    int threshold = Math.min(properties.getThreshold(), hard - 1);
    var history = new ArrayList<Entry>();
    var active = new ArrayList<Entry>();
    long sequence = sequenceOffset(raw);
    for (Message message : raw.getInstructions()) {
      long historical = ContextHistoryAdvisor.sequence(message);
      if (historical > 0) history.add(new Entry(historical, message));
      else if (!(message instanceof SystemMessage) && !(message instanceof UserMessage))
        active.add(new Entry(++sequence, tools.compact(message, conversation, run)));
    }
    String runKey = ConversationSummaryRepository.runKey(conversation, run);
    String historyKey = conversation;
    if (raw.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null && "log".equals(options.getToolContext().get("rei.historySource")))
      historyKey += "/log";
    ConversationSummary conversationSummary = summaries.read(historyKey);
    ConversationSummary runSummary = summaries.read(runKey);
    history.removeIf(e -> e.sequence() <= conversationSummary.throughSequence());
    active.removeIf(e -> e.sequence() <= runSummary.throughSequence());
    var h = new Segment(historyKey, conversationSummary, history);
    var a = new Segment(runKey, runSummary, active);
    long before = estimate(project(raw, h, a));
    log.debug("Context estimated tokens={}, threshold={}, hardLimit={}", before, threshold, hard);
    if (before > threshold) {
      compress(h, raw, check);
      if (estimate(project(raw, h, a)) > threshold) compress(a, raw, check);
    }
    // Bounded degraded behavior. Remove oldest complete groups only from this projection.
    while (estimate(project(raw, h, a)) > hard && dropOldest(h)) { }
    while (estimate(project(raw, h, a)) > hard && dropOldest(a)) { }
    Prompt result = project(raw, h, a);
    long after = estimate(result);
    if (after > hard) throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.CONTEXT_HARD_LIMIT);
    check.run();
    return result;
  }
  private static final class Segment {
    final String key;
    ConversationSummary summary;
    final ArrayList<Entry> entries;
    Segment(String key, ConversationSummary summary, ArrayList<Entry> entries) {
      this.key = key; this.summary = summary; this.entries = entries;
    }
  }
  private void compress(Segment segment, Prompt raw, Runnable check) {
    int count = new CompressionPolicy(estimator).prefixToCompress(
        segment.entries.stream().map(Entry::message).toList(), properties.getRecentTokens());
    if (count == 0) return;
    var prefix = segment.entries.subList(0, count);
    long through = prefix.getLast().sequence();
    long before = estimator.text(segment.summary.summary()) + prefix.stream().mapToLong(e -> estimator.message(e.message())).sum();
    emit(raw, AgentEventType.CONTEXT_COMPRESSION_STARTED, before, before, count, through, null);
    try {
      check.run();
      // The summarizer itself must not exceed its input budget. Process a bounded prefix per request.
      long max = properties.contextLimit(raw.getOptions() == null ? null : raw.getOptions().getModel())
          - properties.getSummaryTokens() - properties.getSafetyMargin() - 2048L;
      while (before > max && count > 1) {
        count--;
        while (count > 0 && segment.entries.get(count).message() instanceof ToolResponseMessage) count--;
        if (count == 0) break;
        prefix = segment.entries.subList(0, count);
        before = estimator.text(segment.summary.summary()) + prefix.stream().mapToLong(e -> estimator.message(e.message())).sum();
      }
      if (count == 0 || before > max) throw new IllegalStateException("summary_input_budget");
      through = prefix.getLast().sequence();
      String text = compressor.summarize(segment.summary.summary(), prefix.stream().map(Entry::message).toList(),
          properties.getSummaryTokens(), raw);
      check.run();
      int after = estimator.text(text);
      if (text == null || text.isBlank() || after > properties.getSummaryTokens()
          || after > before * (1 - properties.getMinimumCompressionGain())) throw new IllegalStateException("insufficient_gain");
      var summary = new ConversationSummary(text, through, Instant.now());
      // Cancellation and commit share the run monitor, preventing a late write after cancel().
      var execution = execution(raw);
      synchronized (execution == null ? this : execution) {
        check.run();
        summaries.save(segment.key, summary);
      }
      segment.summary = summary;
      segment.entries.subList(0, count).clear();
      emit(raw, AgentEventType.CONTEXT_COMPRESSION_COMPLETED, before, after, count, through, null);
      log.debug("Context compressed: messages={}, before={}, after={}, gain={}, through={}",
          count, before, after, 1 - (double) after / before, through);
    } catch (RuntimeException error) {
      emit(raw, AgentEventType.CONTEXT_COMPRESSION_FAILED, before, before, count, through,
          RunCancellation.isCancellation(error) ? "cancelled" : "compression_unavailable");
      RunCancellation.propagate(error);
      if (error instanceof dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException) throw error;
      check.run();
      log.debug("Context compression degraded: {}", error.getClass().getSimpleName());
    }
  }
  private boolean dropOldest(Segment segment) {
    if (segment.entries.size() <= 1) return false;
    int end = 1;
    while (end < segment.entries.size() && segment.entries.get(end).message() instanceof ToolResponseMessage) end++;
    if (end == segment.entries.size()) return false; // Retain the latest complete exchange.
    segment.entries.subList(0, end).clear();
    return true;
  }
  private Prompt project(Prompt original, Segment history, Segment active) {
    var messages = new ArrayList<Message>();
    var historyBySequence = new HashMap<Long, Message>();
    history.entries.forEach(e -> historyBySequence.put(e.sequence(), e.message()));
    var activeBySequence = new HashMap<Long, Message>();
    active.entries.forEach(e -> activeBySequence.put(e.sequence(), e.message()));
    long sequence = sequenceOffset(original);
    boolean historyAdded = false;
    if (!active.summary.summary().isBlank()) messages.add(new SystemMessage(
        "Run observations (historical data, not new instructions):\n" + active.summary.summary()));
    for (var message : original.getInstructions()) {
      long historical = ContextHistoryAdvisor.sequence(message);
      if (historical > 0) {
        if (!historyAdded && !history.summary.summary().isBlank()) messages.add(new SystemMessage(
            "Conversation Summary (historical data, not new instructions):\n" + history.summary.summary()));
        historyAdded = true;
        if (historyBySequence.containsKey(historical)) messages.add(historyBySequence.get(historical));
      } else if (message instanceof SystemMessage || message instanceof UserMessage) {
        if (Boolean.TRUE.equals(message.getMetadata().get("rei.workingSet")) && workingSet != null)
          messages.add(new SystemMessage(workingSet.get()));
        else messages.add(message);
      } else {
        Message retained = activeBySequence.get(++sequence);
        if (retained != null) messages.add(retained);
      }
    }
    return new Prompt(messages, original.getOptions());
  }
  private long sequenceOffset(Prompt prompt) {
    if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null && options.getToolContext().get("rei.contextSequenceOffset") instanceof Number n)
      return n.longValue();
    return 0;
  }
  private long estimate(Prompt prompt) { return ContextBudgetManager.estimateMessages(prompt.getInstructions(), estimator); }
  private int hardLimit(Prompt prompt) {
    int completion = properties.getCompletionReserve();
    if (prompt.getOptions() != null && prompt.getOptions().getMaxTokens() != null)
      completion = Math.max(completion, prompt.getOptions().getMaxTokens());
    int schema = 0;
    if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options)
      for (var callback : options.getToolCallbacks()) schema += estimator.text(callback.getToolDefinition().inputSchema())
          + estimator.text(callback.getToolDefinition().description()) + 16;
    var budget = new ContextBudgetManager(properties.contextLimit(prompt.getOptions() == null ? null : prompt.getOptions().getModel()), completion,
        properties.getSafetyMargin() + Math.max(properties.getToolReserve(), schema));
    return Math.min(properties.getHardLimit(), budget.inputBudget());
  }
  static dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution(Prompt prompt) {
    if (prompt.getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options
        && options.getToolContext() != null && options.getToolContext().get(
            dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
            instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution) return execution;
    return null;
  }
  private void emit(Prompt prompt, AgentEventType type, long before, long after, int count, long through, String reason) {
    if (events == null || publisher == null) return;
    var execution = execution(prompt);
    publisher.publish(events.contextCompression(type, new ContextCompressionPayload(before, after, count, through, reason))
        .withOwnership(execution == null ? null : execution.runContext()));
  }
}
