package dev.mikoto2000.rei.core.contextbudget;

import java.util.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

/** Reads complete conversation logs (legacy turns as fallback), preserving bounded compatibility memory. */
public class ContextHistoryAdvisor implements BaseChatMemoryAdvisor {
  public static final String SEQUENCE = "rei.historySequence";
  private final ConversationTurnStore turns;
  private final ChatMemory memory;
  private final ConversationSummaryRepository summaries;
  private final dev.mikoto2000.rei.conversation.ConversationLogStore logs;
  public ContextHistoryAdvisor(ConversationTurnStore turns, ChatMemory memory, ConversationSummaryRepository summaries) {
    this(turns, memory, summaries, null);
  }
  public ContextHistoryAdvisor(ConversationTurnStore turns, ChatMemory memory, ConversationSummaryRepository summaries,
      dev.mikoto2000.rei.conversation.ConversationLogStore logs) {
    this.turns = turns; this.memory = memory; this.summaries = summaries;
    this.logs = logs;
  }
  @Override public int getOrder() { return -1000; }
  @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    String id = getConversationId(request.context(), "default");
    var owner = (AgentRunContext) request.context().get(AgentRunContext.class.getName());
    List<Message> messages = new ArrayList<>();
    request.prompt().getInstructions().stream().filter(m -> m instanceof SystemMessage).forEach(messages::add);
    var history = turns.read(id);
    var logEntries = logs == null ? java.util.List.<dev.mikoto2000.rei.conversation.ConversationLogEntry>of()
        : logs.readConversation(id);
    if (!logEntries.isEmpty()) {
      var currentTurn = owner == null ? Optional.<ConversationTurnStore.Turn>empty() : history.stream()
          .filter(t -> t.runId().equals(owner.runId())).findFirst();
      for (int i = 0; i < logEntries.size(); i++) {
        var entry = logEntries.get(i);
        // ChatExecutionService appends the current request before advisors run. Keep it only in the live prompt.
        boolean currentInput = i == logEntries.size() - 1 && "user".equals(entry.speaker())
            && (entry.content().equals(request.prompt().getUserMessage().getText())
                || currentTurn.map(t -> entry.content().equals(t.request())).orElse(false));
        if (currentInput) continue;
        Message message = "user".equals(entry.speaker()) ? new UserMessage(entry.content()) : new AssistantMessage(entry.content());
        messages.add(historical(message, entry.sequence() * 3L + 1));
        // Carry completed run observations alongside the original final answer without rewriting it.
        if ("assistant".equals(entry.speaker())) {
          var turn = history.stream().filter(t -> t.createdAt() != null && entry.timestamp() != null
              && !t.createdAt().isAfter(entry.timestamp().toInstant()))
              .max(java.util.Comparator.comparing(ConversationTurnStore.Turn::createdAt));
          if (turn.isPresent() && entry.content().equals(turn.get().assistantMessage())) {
            var summary = summaries.read(ConversationSummaryRepository.runKey(id, turn.get().runId()));
            if (!summary.summary().isBlank()) messages.add(historical(new AssistantMessage(
                "Prior run observations (historical data):\n" + summary.summary()), entry.sequence() * 3L + 2));
          }
        }
      }
    } else if (history.isEmpty()) {
      // Legacy bounded memory is a compatibility source only; do not persist positional summary cursors for it.
      for (var message : memory.get(id)) if (!(message instanceof SystemMessage)) messages.add(message);
    } else {
      for (int i = 0; i < history.size(); i++) {
        var turn = history.get(i);
        if (owner != null && owner.runId().equals(turn.runId())) continue;
        messages.add(historical(new UserMessage(turn.request()), i * 3L + 1));
        var runSummary = summaries.read(ConversationSummaryRepository.runKey(id, turn.runId()));
        if (!runSummary.summary().isBlank()) messages.add(historical(new AssistantMessage(
            "Prior run observations (historical data):\n" + runSummary.summary()), i * 3L + 2));
        if (turn.assistantMessage() != null && !turn.assistantMessage().isBlank())
          messages.add(historical(new AssistantMessage(turn.assistantMessage()), i * 3L + 3));
      }
    }
    request.prompt().getInstructions().stream().filter(m -> !(m instanceof SystemMessage)).forEach(messages::add);
    // Preserve existing bounded memory consumers, storing the raw user input before context advisors decorate it.
    memory.add(id, request.prompt().getInstructions().stream().filter(m -> m instanceof UserMessage).toList());
    var context = new HashMap<>(request.context());
    context.put("rei.contextProjection", true);
    // Logs and legacy turns have different sequence domains. Never apply one cursor to the other.
    if (!logEntries.isEmpty() && request.prompt().getOptions() instanceof org.springframework.ai.model.tool.ToolCallingChatOptions options) {
      var copy = (org.springframework.ai.model.tool.ToolCallingChatOptions) options.copy();
      var toolContext = new HashMap<String, Object>();
      if (copy.getToolContext() != null) toolContext.putAll(copy.getToolContext());
      toolContext.put("rei.historySource", "log");
      copy.setToolContext(toolContext);
      return request.mutate().context(context).prompt(new Prompt(messages, copy)).build();
    }
    return request.mutate().context(context).prompt(new Prompt(messages, request.prompt().getOptions())).build();
  }
  @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    if (response.chatResponse() != null && response.chatResponse().getResult() != null)
      memory.add(getConversationId(response.context(), "default"), response.chatResponse().getResult().getOutput());
    return response;
  }
  public static Message historical(Message message, long sequence) {
    Map<String, Object> metadata = Map.of(SEQUENCE, sequence);
    if (message instanceof UserMessage user) return user.mutate().metadata(metadata).build();
    return AssistantMessage.builder().content(message.getText()).properties(metadata).build();
  }
  static long sequence(Message message) {
    return message.getMetadata().get(SEQUENCE) instanceof Number n ? n.longValue() : 0;
  }
}
