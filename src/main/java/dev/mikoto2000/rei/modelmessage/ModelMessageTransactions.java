package dev.mikoto2000.rei.modelmessage;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** Pure transaction boundaries shared by context compression and checkpoint recovery. */
public final class ModelMessageTransactions {
  private ModelMessageTransactions() {}

  /**
   * A saved request or complete tool transaction can continue; unfinished calls need
   * reconciliation.
   */
  public static boolean completeProtocol(List<Message> messages) {
    if (messages.isEmpty()) return false;
    var first =
        messages.stream()
            .filter(
                message -> !(message instanceof org.springframework.ai.chat.messages.SystemMessage))
            .findFirst();
    if (first.isEmpty()
        || !(first.orElseThrow() instanceof org.springframework.ai.chat.messages.UserMessage))
      return false;
    var boundaries = completePrefixes(messages);
    if (!boundaries.isEmpty() && boundaries.getLast() == messages.size()) return true;
    return messages.getLast() instanceof org.springframework.ai.chat.messages.UserMessage
        && (messages.size() == 1
            || !boundaries.isEmpty() && boundaries.getLast() == messages.size() - 1);
  }

  public static List<Integer> completePrefixes(List<Message> messages) {
    var boundaries = new java.util.ArrayList<Integer>();
    var pending = new java.util.HashMap<String, String>();
    for (int i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      if (message instanceof org.springframework.ai.chat.messages.UserMessage) {
        if (!pending.isEmpty()) return boundaries;
      } else if (message
          instanceof org.springframework.ai.chat.messages.AssistantMessage assistant) {
        if (!pending.isEmpty()) return boundaries;
        for (var call : assistant.getToolCalls()) {
          if (call.id() == null
              || call.id().isBlank()
              || call.name() == null
              || call.name().isBlank()
              || pending.putIfAbsent(call.id(), call.name()) != null) return List.of();
        }
        if (pending.isEmpty()) boundaries.add(i + 1);
      } else if (message instanceof ToolResponseMessage tool) {
        if (pending.isEmpty() || tool.getResponses().isEmpty()) return List.of();
        for (var result : tool.getResponses()) {
          if (result.id() == null
              || result.name() == null
              || !result.name().equals(pending.remove(result.id()))) return List.of();
        }
        if (pending.isEmpty()) boundaries.add(i + 1);
      }
    }
    return boundaries;
  }
}
