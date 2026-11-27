package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.chatmemory.ConversationMemory;
import dev.mikoto2000.rei.conversation.ConversationLogEntry;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
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
    boolean useLogs =
        !logEntries.isEmpty() || history.stream().anyMatch(t -> hasProtocolManifest(t.metadata()));
    ModelMessagePayloadStore.HistoryReplayGrant historyGrant = null;
    if (useLogs) {
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
      var protocol =
          protocolProjection(owner, request.prompt().getOptions(), history, logEntries, through);
      historyGrant = protocol.grant();
      int historicalStart = messages.size();
      messages.addAll(protocol.messages().values());
      boolean historyAnchor =
          protocol.messages().keySet().stream().anyMatch(sequence -> sequence <= through);
      for (int i = 0; i < logEntries.size(); i++) {
        var entry = logEntries.get(i);
        String protocolRun = entry.metadata().get(ModelMessagePayloadStore.HISTORY_RUN);
        if (ConversationLogStore.PROTOCOL_SPEAKER.equals(entry.speaker())
            || protocolRun != null
                && (protocol.hidden().contains(protocolRun)
                    || protocol.replaced().contains(protocolRun))) continue;
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
          // Display metadata is not model protocol state. Keep provenance only;
          // restore() supplies protocol metadata from a validated payload.
          var properties = new HashMap<String, Object>();
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
          if (sequence > through
              && "assistant".equals(entry.speaker())
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
              historical(
                  sequence > through
                      ? restore(owner, modelMetadata, entry.speaker(), message)
                      : message,
                  sequence));
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
      var ordered =
          messages.subList(historicalStart, messages.size()).stream()
              .sorted(java.util.Comparator.comparingLong(ContextHistoryAdvisor::sequence))
              .toList();
      messages.subList(historicalStart, messages.size()).clear();
      messages.addAll(ordered);
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
      long through =
          summaries
              .read(
                  dev.mikoto2000.rei.context.CancelledTaskHistory.summaryKey(
                      id, cancelled.generation()))
              .throughSequence();
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
          var properties = new HashMap<String, Object>();
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
    if (useLogs
        && request.prompt().getOptions()
            instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
      var copy = options;
      var toolContext = new HashMap<String, Object>();
      if (copy.getToolContext() != null) toolContext.putAll(copy.getToolContext());
      toolContext.put("rei.historySource", "log");
      if (historyGrant != null) {
        var existing = toolContext.get(ModelMessagePayloadStore.HISTORY_GRANT_CONTEXT_KEY);
        toolContext.put(
            ModelMessagePayloadStore.HISTORY_GRANT_CONTEXT_KEY,
            existing instanceof ModelMessagePayloadStore.HistoryReplayGrant checkpoint
                ? historyGrant.merge(checkpoint)
                : historyGrant);
      }
      copy = options.mutate().toolContext(toolContext).build();
      return request.mutate().context(context).prompt(new Prompt(messages, copy)).build();
    }
    return request
        .mutate()
        .context(context)
        .prompt(new Prompt(messages, request.prompt().getOptions()))
        .build();
  }

  private record ProtocolProjection(
      java.util.Set<String> hidden,
      java.util.Set<String> replaced,
      java.util.Map<Long, Message> messages,
      ModelMessagePayloadStore.HistoryReplayGrant grant) {}

  private static boolean hasProtocolManifest(Map<String, String> metadata) {
    return metadata.containsKey(ConversationLogStore.PROTOCOL_VERSION)
        || metadata.containsKey(ConversationLogStore.PROTOCOL_COUNT)
        || metadata.containsKey(ConversationLogStore.PROTOCOL_FIRST)
        || metadata.containsKey(ConversationLogStore.PROTOCOL_LAST);
  }

  private static IllegalStateException protocolRejected() {
    return new IllegalStateException("Model message persistence rejected");
  }

  private static long protocolPosition(Map<String, String> metadata, String key) {
    try {
      long position = Long.parseLong(metadata.get(key));
      if (position < 1 || position > (Long.MAX_VALUE - 2) / 3) throw protocolRejected();
      return position;
    } catch (NumberFormatException invalid) {
      throw protocolRejected();
    }
  }

  /** Completed lifecycle metadata owns visibility; protocol pointers do not publish a Run. */
  private ProtocolProjection protocolProjection(
      AgentRunContext owner,
      org.springframework.ai.chat.prompt.ChatOptions requestOptions,
      List<ConversationTurnStore.Turn> turns,
      List<ConversationLogEntry> logs,
      long through) {
    var context =
        requestOptions instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools
            ? tools.getToolContext()
            : null;
    var target = context == null ? null : context.get(ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY);
    var boundary =
        context == null ? null : context.get(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY);
    var byRun = new java.util.HashMap<String, ConversationTurnStore.Turn>();
    for (var turn : turns) {
      var previous = byRun.putIfAbsent(turn.runId(), turn);
      if (previous != null
          && (hasProtocolManifest(previous.metadata()) || hasProtocolManifest(turn.metadata())))
        throw protocolRejected();
    }
    var rowsByRun = new java.util.HashMap<String, List<ConversationLogEntry>>();
    var nativeRuns = new java.util.HashSet<String>();
    for (var entry : logs) {
      var run = entry.metadata().get(ModelMessagePayloadStore.HISTORY_RUN);
      if (run == null) continue;
      nativeRuns.add(run);
      if (ConversationLogStore.PROTOCOL_SPEAKER.equals(entry.speaker()))
        rowsByRun.computeIfAbsent(run, ignored -> new ArrayList<>()).add(entry);
    }
    for (var turn : turns) if (hasProtocolManifest(turn.metadata())) nativeRuns.add(turn.runId());
    var hidden = new java.util.HashSet<String>();
    var replaced = new java.util.HashSet<String>();
    var projected = new java.util.TreeMap<Long, Message>();
    var references = new ArrayList<Map<String, String>>();
    ModelMessagePayloadStore store = null;
    for (String run : nativeRuns) {
      var turn = byRun.get(run);
      if (turn == null || !turn.completed() || owner != null && owner.runId().equals(run)) {
        hidden.add(run);
        continue;
      }
      var metadata = turn.metadata();
      if (!hasProtocolManifest(metadata)) continue;
      if (!"1".equals(metadata.get(ConversationLogStore.PROTOCOL_VERSION))
          || !run.equals(metadata.get(ModelMessagePayloadStore.HISTORY_RUN)))
        throw protocolRejected();
      long first = protocolPosition(metadata, ConversationLogStore.PROTOCOL_FIRST);
      long last = protocolPosition(metadata, ConversationLogStore.PROTOCOL_LAST);
      long count = protocolPosition(metadata, ConversationLogStore.PROTOCOL_COUNT);
      String endpoint = metadata.get(ModelMessagePayloadStore.HISTORY_ENDPOINT);
      if (last < first || count > 256 || endpoint == null || endpoint.isBlank())
        throw protocolRejected();
      if (!(target instanceof ModelMessagePayloadStore.Origin origin)) continue;
      if (!origin.endpoint().equals(endpoint)) {
        var rows = rowsByRun.getOrDefault(run, List.of());
        if (last * 3 + 1 > through) {
          if (!(boundary instanceof ModelMessagePayloadStore.Exchange exchange)
              || owner == null
              || !exchange.run().equals(owner.runId())
              || !origin.owner().equals(exchange.ownerKey())
              || !exchange.scope().equals(ModelMessagePayloadStore.Scope.forOwner(owner)))
            throw protocolRejected();
          if (store == null) store = payloads.get();
          if (store == null) throw protocolRejected();
          var operations =
              commonHistory(
                  store, exchange.scope(), rows, run, first, last, count, through, projected);
          if (!operations.isEmpty())
            projected.put(last * 3 + 1, historical(new AssistantMessage(operations), last * 3 + 1));
        }
        // The existing completed display rows retain the common conversation. Native payloads
        // and their history leases stay with the source connection.
        continue;
      }
      if (!(boundary instanceof ModelMessagePayloadStore.Exchange exchange)
          || owner == null
          || !exchange.run().equals(owner.runId())
          || !origin.owner().equals(exchange.ownerKey())
          || !exchange.scope().equals(ModelMessagePayloadStore.Scope.forOwner(owner)))
        throw protocolRejected();
      replaced.add(run);
      var rows = rowsByRun.getOrDefault(run, List.of());
      if (last * 3 + 1 <= through) {
        retireCoveredHistory(exchange, run, rows, first, last, count);
        projected.put(last * 3 + 1, historical(new AssistantMessage(""), last * 3 + 1));
      } else {
        if (rows.size() != count
            || rows.isEmpty()
            || rows.getFirst().sequence() != first
            || rows.getLast().sequence() != last
            || rows.stream()
                    .map(row -> row.metadata().get(ModelMessagePayloadStore.HISTORY_ID))
                    .distinct()
                    .count()
                != count) throw protocolRejected();
        String lastCoveredRole = null;
        boolean uncovered = false;
        for (var row : rows) {
          long sequence = row.sequence() * 3 + 1;
          String role = row.metadata().get(ModelMessagePayloadStore.HISTORY_ROLE);
          if (role == null || !java.util.Set.of("user", "assistant", "tool").contains(role))
            throw protocolRejected();
          if (sequence <= through) {
            lastCoveredRole = role;
            projected.put(sequence, historical(new AssistantMessage(""), sequence));
            continue;
          }
          if (!uncovered
              && ("user".equals(lastCoveredRole) || lastCoveredRole != null && "tool".equals(role)))
            throw protocolRejected();
          uncovered = true;
          if (store == null) store = payloads.get();
          if (store == null) throw protocolRejected();
          var message = store.restoreHistory(exchange.scope(), row.metadata()).orElseThrow();
          if (!message.getMessageType().name().equalsIgnoreCase(role)) throw protocolRejected();
          if (projected.put(sequence, historical(message, sequence)) != null)
            throw protocolRejected();
          references.add(row.metadata());
        }
      }
      var summary =
          summaries.read(ConversationSummaryRepository.runKey(owner.conversationId(), run));
      if (!summary.summary().isBlank() && last * 3 + 2 > through)
        projected.put(
            last * 3 + 2,
            historical(
                new AssistantMessage(
                    "Prior run observations (historical data):\n" + summary.summary()),
                last * 3 + 2));
    }
    ModelMessagePayloadStore.HistoryReplayGrant grant = null;
    if (!references.isEmpty()) {
      if (store == null
          || !(boundary instanceof ModelMessagePayloadStore.Exchange exchange)
          || !(target instanceof ModelMessagePayloadStore.Origin origin)) throw protocolRejected();
      grant = store.historyReplayGrant(exchange, references, origin);
    }
    return new ProtocolProjection(
        java.util.Set.copyOf(hidden),
        java.util.Set.copyOf(replaced),
        java.util.Collections.unmodifiableMap(projected),
        grant);
  }

  private static String commonHistory(
      ModelMessagePayloadStore store,
      ModelMessagePayloadStore.Scope scope,
      List<ConversationLogEntry> rows,
      String run,
      long first,
      long last,
      long count,
      long through,
      Map<Long, Message> projected) {
    if (rows.size() != count
        || rows.isEmpty()
        || rows.getFirst().sequence() != first
        || rows.getLast().sequence() != last) throw incompatibleToolHistory();
    var pending = new java.util.LinkedHashMap<String, AssistantMessage.ToolCall>();
    var seen = new java.util.HashSet<String>();
    var references = new java.util.HashSet<String>();
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var operations = json.createArrayNode();
    long previous = 0;
    long characters = 0;
    for (var row : rows) {
      var metadata = row.metadata();
      String role = metadata.get(ModelMessagePayloadStore.HISTORY_ROLE);
      if (!run.equals(metadata.get(ModelMessagePayloadStore.HISTORY_RUN))
          || !("turn:" + run).equals(metadata.get(ModelMessagePayloadStore.HISTORY_OWNER))
          || !references.add(metadata.get(ModelMessagePayloadStore.HISTORY_ID))
          || row.sequence() <= previous) throw incompatibleToolHistory();
      previous = row.sequence();
      var message = store.restoreHistory(scope, metadata).orElseThrow();
      if (!message.getMessageType().name().equalsIgnoreCase(role)) throw incompatibleToolHistory();
      if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage results) {
        if (results.getResponses().isEmpty()) throw incompatibleToolHistory();
        for (var result : results.getResponses()) {
          var call = pending.remove(result.id());
          if (call == null || !call.name().equals(result.name()) || result.responseData() == null)
            throw incompatibleToolHistory();
          characters +=
              (long) call.name().length()
                  + call.arguments().length()
                  + result.responseData().length();
          if (characters > 131_072) throw incompatibleToolHistory();
          operations
              .addObject()
              .put("operation", call.name())
              .put("arguments", call.arguments())
              .put("result", result.responseData());
        }
      } else {
        if (!pending.isEmpty()) throw incompatibleToolHistory();
        if (message instanceof AssistantMessage assistant) {
          for (var call : assistant.getToolCalls()) {
            if (call.id() == null
                || call.id().isBlank()
                || !seen.add(call.id())
                || !"function".equals(call.type())
                || call.name() == null
                || call.name().isBlank()
                || call.arguments() == null) throw incompatibleToolHistory();
            pending.put(call.id(), call);
          }
        } else if (message instanceof UserMessage user) {
          long sequence = row.sequence() * 3 + 1;
          if (sequence > through && !user.getMedia().isEmpty())
            projected.put(
                sequence,
                historical(
                    UserMessage.builder()
                        .text("Attachments from a completed turn (historical data):")
                        .media(
                            user.getMedia().stream()
                                .map(ContextHistoryAdvisor::commonMedia)
                                .toList())
                        .build(),
                    sequence));
        } else throw incompatibleToolHistory();
      }
    }
    if (!pending.isEmpty()) throw incompatibleToolHistory();
    if (operations.isEmpty()) return "";
    return "Completed tool operations from a previous connection (historical data; already executed):\n"
        + operations;
  }

  private static org.springframework.ai.content.Media commonMedia(
      org.springframework.ai.content.Media source) {
    // Provider file IDs are not portable authority. Keep only explicit data; the destination's
    // input policy and content-bound export approval still admit the projected request.
    var builder = org.springframework.ai.content.Media.builder().mimeType(source.getMimeType());
    if (source.getName() != null) builder.name(source.getName());
    var data = source.getData();
    if (data instanceof byte[] bytes) builder.data(bytes.clone());
    else if (data instanceof String text) builder.data(text);
    else throw incompatibleToolHistory();
    return builder.build();
  }

  private static IllegalStateException incompatibleToolHistory() {
    return new IllegalStateException(
        "Completed history is incomplete or incompatible; start a new session");
  }

  private static void retireCoveredHistory(
      ModelMessagePayloadStore.Exchange exchange,
      String run,
      List<ConversationLogEntry> rows,
      long first,
      long last,
      long count) {
    // Missing or damaged log evidence keeps pins. An already committed summary remains usable.
    try {
      if (rows.size() != count
          || rows.isEmpty()
          || rows.getFirst().sequence() != first
          || rows.getLast().sequence() != last) return;
      String anchor = "turn:" + run;
      var refs = new ArrayList<ModelMessagePayloadStore.Ref>();
      var ids = new java.util.HashSet<String>();
      long previous = 0;
      for (var row : rows) {
        var metadata = row.metadata();
        String role = metadata.get(ModelMessagePayloadStore.HISTORY_ROLE);
        String id = metadata.get(ModelMessagePayloadStore.HISTORY_ID);
        if (!run.equals(metadata.get(ModelMessagePayloadStore.HISTORY_RUN))
            || !anchor.equals(metadata.get(ModelMessagePayloadStore.HISTORY_OWNER))
            || role == null
            || !java.util.Set.of("user", "assistant", "tool").contains(role)
            || row.sequence() <= previous
            || !ids.add(id)) return;
        previous = row.sequence();
        refs.add(
            new ModelMessagePayloadStore.Ref(
                id, metadata.get(ModelMessagePayloadStore.HISTORY_HASH)));
      }
      exchange.store().releaseHistory(exchange.scope(), anchor, refs);
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(ContextHistoryAdvisor.class)
          .warn("Model message history retirement deferred");
    }
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
