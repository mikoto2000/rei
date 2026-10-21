package dev.mikoto2000.rei.core.contextbudget;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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

  /** やり取り一覧。 */
  private final ConversationTurnStore turns;

  /** 記憶。 */
  private final ChatMemory memory;

  /** 要約一覧。 */
  private final ConversationSummaryRepository summaries;

  /** ログ一覧。 */
  private final dev.mikoto2000.rei.conversation.ConversationLogStore logs;

  /** モデルへ渡す文脈の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ContextHistoryAdvisor(
      ConversationTurnStore turns, ChatMemory memory, ConversationSummaryRepository summaries) {
    this(turns, memory, summaries, null);
  }

  /** 指定された値や利用先を使って、ContextHistoryAdvisorを初期化する。 */
  public ContextHistoryAdvisor(
      ConversationTurnStore turns,
      ChatMemory memory,
      ConversationSummaryRepository summaries,
      dev.mikoto2000.rei.conversation.ConversationLogStore logs) {
    this.turns = turns;
    this.memory = memory;
    this.summaries = summaries;
    this.logs = logs;
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
        java.util.Objects.toString(request.context().get(ChatMemory.CONVERSATION_ID), "default");
    var owner = (AgentRunContext) request.context().get(AgentRunContext.class.getName());
    List<Message> messages = new ArrayList<>();
    request.prompt().getInstructions().stream()
        .filter(m -> m instanceof SystemMessage)
        .forEach(messages::add);
    var history = turns.read(id);
    var logEntries =
        logs == null
            ? java.util.List.<dev.mikoto2000.rei.conversation.ConversationLogEntry>of()
            : logs.readConversation(id);
    if (!logEntries.isEmpty()) {
      var currentTurn =
          owner == null
              ? Optional.<ConversationTurnStore.Turn>empty()
              : history.stream().filter(t -> t.runId().equals(owner.runId())).findFirst();
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
        messages.add(historical(message, entry.sequence() * 3L + 1));
        // Carry completed run observations alongside the original final answer without rewriting
        // it.
        if ("assistant".equals(entry.speaker())) {
          var turn =
              history.stream()
                  .filter(
                      t ->
                          t.createdAt() != null
                              && entry.timestamp() != null
                              && !t.createdAt().isAfter(entry.timestamp().toInstant()))
                  .max(java.util.Comparator.comparing(ConversationTurnStore.Turn::createdAt));
          if (turn.isPresent() && entry.content().equals(turn.get().assistantMessage())) {
            var summary =
                summaries.read(ConversationSummaryRepository.runKey(id, turn.get().runId()));
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
      for (var message : memory.get(id))
        if (!(message instanceof SystemMessage)) messages.add(message);
    } else {
      for (int i = 0; i < history.size(); i++) {
        var turn = history.get(i);
        if (owner != null && owner.runId().equals(turn.runId())) continue;
        if (turn.request() != null && !turn.request().isBlank())
          messages.add(historical(new UserMessage(turn.request()), i * 3L + 1));
        var runSummary = summaries.read(ConversationSummaryRepository.runKey(id, turn.runId()));
        if (!runSummary.summary().isBlank())
          messages.add(
              historical(
                  new AssistantMessage(
                      "Prior run observations (historical data):\n" + runSummary.summary()),
                  i * 3L + 2));
        if (turn.assistantMessage() != null && !turn.assistantMessage().isBlank()) {
          var properties = new HashMap<String, Object>(turn.metadata());
          if (turn.source() != null) properties.put("source", turn.source());
          if (turn.sourceId() != null) properties.put("sourceId", turn.sourceId());
          messages.add(
              historical(
                  AssistantMessage.builder()
                      .content(turn.assistantMessage())
                      .properties(properties)
                      .build(),
                  i * 3L + 3));
        }
      }
    }
    request.prompt().getInstructions().stream()
        .filter(m -> !(m instanceof SystemMessage))
        .forEach(messages::add);
    // Preserve existing bounded memory consumers, storing the raw user input before context
    // advisors decorate it.
    memory.add(
        id,
        request.prompt().getInstructions().stream().filter(m -> m instanceof UserMessage).toList());
    var context = new HashMap<>(request.context());
    context.put("rei.contextProjection", true);
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

  /** モデルへ渡す文脈の管理の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    if (response.chatResponse() != null && response.chatResponse().getResult() != null)
      memory.add(
          java.util.Objects.toString(response.context().get(ChatMemory.CONVERSATION_ID), "default"),
          response.chatResponse().getResult().getOutput());
    return response;
  }

  /** モデルへ渡す文脈の管理で用いるhistoricalを返す。 */
  public static Message historical(Message message, long sequence) {
    Map<String, Object> metadata = new HashMap<>(message.getMetadata());
    metadata.put(SEQUENCE, sequence);
    if (message instanceof UserMessage user) return user.mutate().metadata(metadata).build();
    return AssistantMessage.builder().content(message.getText()).properties(metadata).build();
  }

  /** モデルへ渡す文脈の管理で用いる発生順序を返す。 */
  static long sequence(Message message) {
    return message.getMetadata().get(SEQUENCE) instanceof Number n ? n.longValue() : 0;
  }
}
