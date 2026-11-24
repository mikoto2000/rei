package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.chatmemory.ConversationMemory;
import dev.mikoto2000.rei.conversation.ConversationLogEntry;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * モデルへ渡す文脈の管理に必要な文脈履歴対話の補助を扱う。
 *
 * <p>Reads complete conversation logs (legacy turns as fallback), preserving bounded compatibility
 * memory.
 */
public class ContextHistoryAdvisor implements BaseChatMemoryAdvisor {
  /** 発生順序。 */
  public static final String SEQUENCE = "rei.historySequence";

  static final String LEGACY_HISTORY = "rei.legacyHistory";

  /** やり取り一覧。 */
  private final Function<String, List<ConversationTurnStore.Turn>> readTurns;

  /** 記憶。 */
  private final Function<String, List<Message>> readLegacyMemory;

  private final BiConsumer<String, List<Message>> rememberInput;
  private final BiConsumer<String, Message> rememberOutput;

  /** 要約一覧。 */
  private final ConversationSummaryRepository summaries;

  /** ログ一覧。 */
  private final Function<String, List<ConversationLogEntry>> readHistory;

  private java.util.function.Supplier<dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore>
      payloads;

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ContextHistoryAdvisor(
      ConversationTurnStore turns,
      ConversationMemory memory,
      ConversationSummaryRepository summaries) {
    this(turns, memory, summaries, null);
  }

  /** 指定された値や利用先を使って、ContextHistoryAdvisorを初期化する。 */
  public ContextHistoryAdvisor(
      ConversationTurnStore turns,
      ConversationMemory memory,
      ConversationSummaryRepository summaries,
      dev.mikoto2000.rei.conversation.ConversationLogStore logs) {
    this(turns, memory, summaries, logs, () -> null);
  }

  public ContextHistoryAdvisor(
      ConversationTurnStore turns,
      ConversationMemory memory,
      ConversationSummaryRepository summaries,
      dev.mikoto2000.rei.conversation.ConversationLogStore logs,
      java.util.function.Supplier<dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore>
          payloads) {
    this.readTurns = conversation -> turns.read(conversation);
    this.readLegacyMemory = id -> memory.get(id);
    this.rememberInput = (id, messages) -> memory.add(id, messages);
    this.rememberOutput = (id, message) -> memory.add(id, message);
    this.summaries = summaries;
    this.readHistory = id -> logs == null ? List.of() : logs.readConversation(id);
    this.payloads = payloads == null ? () -> null : payloads;
  }

  @org.springframework.beans.factory.annotation.Autowired
  void configureModelMessagePayloads(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore>
          payloads) {
    this.payloads = payloads::getObject;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -1000;
  }

  /** モデルへ渡す文脈の管理の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    String id =
        java.util.Objects.toString(
            request
                .context()
                .get(org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID),
            "default");
    var owner = (AgentRunContext) request.context().get(AgentRunContext.class.getName());
    List<Message> messages = new ArrayList<>();
    request.prompt().getInstructions().stream()
        .filter(m -> m instanceof SystemMessage)
        .forEach(messages::add);
    var history = readTurns.apply(id);
    var cancelled = ConversationTurnStore.promptHistoryFor(history);
    if (!cancelled.generation().isEmpty())
      messages.add(
          SystemMessage.builder()
              .text(
                  "Cancelled and failed task history is archived. Answer only the current user"
                      + " request; retrieve archived attempts only at the user's explicit request.")
              .metadata(
                  Map.of(
                      dev.mikoto2000.rei.context.CancelledTaskHistory.GENERATION,
                      cancelled.generation()))
              .build());
    var logEntries = readHistory.apply(id);
    if (!logEntries.isEmpty()) {
      String historyKey = id;
      if (request.prompt().getOptions()
          instanceof org.springframework.ai.model.tool.ToolCallingChatOptions) historyKey += "/log";
      historyKey =
          dev.mikoto2000.rei.context.CancelledTaskHistory.summaryKey(
              historyKey, cancelled.generation());
      long through = summaries.read(historyKey).throughSequence();
      var hiddenAt = cancelled.indexedTimestampFilter();
      var timeline = new java.util.TreeMap<java.time.Instant, ConversationTurnStore.Turn>();
      var ambiguousProtocolTimes = new java.util.HashSet<java.time.Instant>();
      for (var turn : history)
        if (turn.createdAt() != null
            && !dev.mikoto2000.rei.conversation.ConversationLogStore.BEHAVIOR_NOTIFICATION.equals(
                turn.source())) {
          var previous = timeline.putIfAbsent(turn.createdAt(), turn);
          if (previous != null
              && (dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.hasHistoryReference(
                      previous.metadata())
                  || dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.hasHistoryReference(
                      turn.metadata()))) ambiguousProtocolTimes.add(turn.createdAt());
        }
      var currentTurn =
          owner == null
              ? Optional.<ConversationTurnStore.Turn>empty()
              : history.stream().filter(t -> t.runId().equals(owner.runId())).findFirst();
      boolean historyAnchor = false;
      for (int i = 0; i < logEntries.size(); i++) {
        var entry = logEntries.get(i);
        // ChatExecutionService appends the current request before advisors run. Keep it only in the
        // live prompt.
        boolean currentInput =
            i == logEntries.size() - 1
                && "user".equals(entry.speaker())
                && (entry.content().equals(request.prompt().getUserMessage().getText())
                    || currentTurn.map(t -> entry.content().equals(t.request())).orElse(false));
        if (currentInput) continue;
        // Notifications are independent history entries, not boundaries of an executing attempt.
        // Keep the notification itself without letting it make later failed-run logs visible.
        if (!dev.mikoto2000.rei.conversation.ConversationLogStore.BEHAVIOR_NOTIFICATION.equals(
                entry.source())
            && hiddenAt.test(entry.timestamp() == null ? null : entry.timestamp().toInstant()))
          continue;
        long sequence = entry.sequence() * 3L + 1;
        // One covered entry anchors the existing assembler's summary insertion; it is removed
        // there by the cursor and is never delivered alongside the summary.
        if (sequence > through || !historyAnchor) {
          var properties = new HashMap<String, Object>(entry.metadata());
          if (entry.source() != null) properties.put("source", entry.source());
          if (entry.sourceId() != null) properties.put("sourceId", entry.sourceId());
          Message message =
              "user".equals(entry.speaker())
                  ? new UserMessage(entry.content())
                  : AssistantMessage.builder()
                      .content(entry.content())
                      .properties(properties)
                      .build();
          var modelMetadata = entry.metadata();
          if (sequence > through && "assistant".equals(entry.speaker())
              && !dev.mikoto2000.rei.conversation.ConversationLogStore.BEHAVIOR_NOTIFICATION.equals(
                  entry.source())
              && !dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.hasHistoryReference(
                  modelMetadata)
              && entry.timestamp() != null) {
            var previousTurn = timeline.floorEntry(entry.timestamp().toInstant());
            if (previousTurn != null && ambiguousProtocolTimes.contains(previousTurn.getKey()))
              throw new IllegalStateException("Model message persistence rejected");
            var savedTurn = previousTurn == null ? null : previousTurn.getValue();
            if (savedTurn != null
                && savedTurn.completed()
                && entry.content().equals(savedTurn.assistantMessage()))
              modelMetadata = savedTurn.metadata();
          }
          messages.add(
              historical(sequence > through ? restore(owner, modelMetadata, entry.speaker(), message) : message, sequence));
          historyAnchor = true;
        }
        // Carry completed run observations alongside the original final answer without rewriting
        // it.
        if ("assistant".equals(entry.speaker())
            && !dev.mikoto2000.rei.conversation.ConversationLogStore.BEHAVIOR_NOTIFICATION.equals(
                entry.source())
            && sequence + 1 > through) {
          var previous =
              entry.timestamp() == null ? null : timeline.floorEntry(entry.timestamp().toInstant());
          var turn = previous == null ? null : previous.getValue();
          if (turn != null && turn.completed() && entry.content().equals(turn.assistantMessage())) {
            var summary = summaries.read(ConversationSummaryRepository.runKey(id, turn.runId()));
            if (!summary.summary().isBlank())
              messages.add(
                  historical(
                      new AssistantMessage(
                          "Prior run observations (historical data):\n" + summary.summary()),
                      entry.sequence() * 3L + 2));
          }
        }
      }
    } else if (history.isEmpty()) {
      // Legacy bounded memory is a compatibility source only; do not persist positional summary
      // cursors for it.
      for (var message : readLegacyMemory.apply(id)) {
        if (message instanceof UserMessage user) {
          var metadata = new HashMap<>(user.getMetadata());
          metadata.put(LEGACY_HISTORY, true);
          messages.add(user.mutate().metadata(metadata).build());
        } else if (!(message instanceof SystemMessage)) {
          messages.add(message);
        }
      }
    } else {
      long through = summaries.read(dev.mikoto2000.rei.context.CancelledTaskHistory.summaryKey(id, cancelled.generation())).throughSequence();
      for (int i = 0; i < history.size(); i++) {
        var turn = history.get(i);
        if (owner != null && owner.runId().equals(turn.runId())) continue;
        if (cancelled.hides(turn.runId())) continue;
        if (turn.request() != null && !turn.request().isBlank())
          messages.add(historical(new UserMessage(turn.request()), i * 3L + 1));
        var runSummary = summaries.read(ConversationSummaryRepository.runKey(id, turn.runId()));
        if (!runSummary.summary().isBlank())
          messages.add(
              historical(
                  new AssistantMessage(
                      "Prior run observations (historical data):\n" + runSummary.summary()),
                  i * 3L + 2));
        if ((turn.assistantMessage() != null && !turn.assistantMessage().isBlank())
            || dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.hasHistoryReference(
                turn.metadata())) {
          var properties = new HashMap<String, Object>(turn.metadata());
          if (turn.source() != null) properties.put("source", turn.source());
          if (turn.sourceId() != null) properties.put("sourceId", turn.sourceId());
          messages.add(
              historical(
                  restore(
                      owner,
                      i * 3L + 3 > through ? turn.metadata() : Map.of(),
                      "assistant",
                      AssistantMessage.builder()
                          .content(java.util.Objects.toString(turn.assistantMessage(), ""))
                          .properties(properties)
                          .build()),
                  i * 3L + 3));
        }
      }
    }
    request.prompt().getInstructions().stream()
        .filter(m -> !(m instanceof SystemMessage))
        .forEach(messages::add);
    // Preserve existing bounded memory consumers, storing the raw user input before context
    // advisors decorate it.
    rememberInput.accept(
        id,
        request.prompt().getInstructions().stream().filter(m -> m instanceof UserMessage).toList());
    var context = new HashMap<>(request.context());
    context.put("rei.contextProjection", true);
    context.put(dev.mikoto2000.rei.context.CancelledTaskHistory.KEY, cancelled);
    // Logs and legacy turns have different sequence domains. Never apply one cursor to the other.
    if (!logEntries.isEmpty()
        && request.prompt().getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
      var copy = options;
      var toolContext = new HashMap<String, Object>();
      if (copy.getToolContext() != null) toolContext.putAll(copy.getToolContext());
      toolContext.put("rei.historySource", "log");
      copy = options.mutate().toolContext(toolContext).build();
      return request.mutate().context(context).prompt(new Prompt(messages, copy)).build();
    }
    return request
        .mutate()
        .context(context)
        .prompt(new Prompt(messages, request.prompt().getOptions()))
        .build();
  }

  private Message restore(
      AgentRunContext owner, Map<String, String> metadata, String speaker, Message fallback) {
    if (!dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.hasHistoryReference(metadata))
      return fallback;
    if (owner == null) throw new IllegalStateException("Model message persistence rejected");
    var store = payloads.get();
    if (store == null) throw new IllegalStateException("Model message persistence rejected");
    var restored =
        store
            .restoreHistory(
                dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope.forOwner(owner),
                metadata)
            .orElseThrow();
    if (!restored.getMessageType().name().equalsIgnoreCase(speaker))
      throw new IllegalStateException("Model message persistence rejected");
    return restored;
  }

  /** モデルへ渡す文脈の管理の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    var chat = response.chatResponse();
    var generation = chat == null ? null : chat.getResult();
    var output = generation == null ? null : generation.getOutput();
    if (output != null)
      rememberOutput.accept(
          java.util.Objects.toString(
              response
                  .context()
                  .get(
                      org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID),
              "default"),
          output);
    return response;
  }

  /** モデルへ渡す文脈の管理で用いるhistoricalを返す。 */
  public static Message historical(Message message, long sequence) {
    Map<String, Object> metadata = new HashMap<>(message.getMetadata());
    metadata.put(SEQUENCE, sequence);
    if (message instanceof UserMessage user) return user.mutate().metadata(metadata).build();
    if (message instanceof AssistantMessage assistant)
      return assistant.mutate().properties(metadata).build();
    if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool)
      return org.springframework.ai.chat.messages.ToolResponseMessage.builder()
          .responses(tool.getResponses())
          .metadata(metadata)
          .build();
    if (message instanceof SystemMessage system) return system.mutate().metadata(metadata).build();
    throw new IllegalArgumentException("Unsupported historical model message");
  }

  /** モデルへ渡す文脈の管理で用いる発生順序を返す。 */
  static long sequence(Message message) {
    return message.getMetadata().get(SEQUENCE) instanceof Number n ? n.longValue() : 0;
  }
}
