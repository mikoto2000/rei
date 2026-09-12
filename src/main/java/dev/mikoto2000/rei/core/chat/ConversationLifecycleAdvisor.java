package dev.mikoto2000.rei.core.chat;

import java.util.ArrayList;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;

/** Render lifecycle after memory and working context assembly; never persist it as a user request. */
public final class ConversationLifecycleAdvisor implements BaseAdvisor {
  private final ConversationTurnStore turns;
  private final String conversationId;
  public ConversationLifecycleAdvisor(ConversationTurnStore turns, String conversationId) {
    this.turns = turns;
    this.conversationId = conversationId;
  }
  @Override public int getOrder() { return 100; }
  @Override public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    String context = turns.cancelledContext(conversationId);
    if (context.isEmpty()) return request;
    var messages = new ArrayList<Message>();
    String system = request.prompt().getSystemMessage().getText();
    messages.add(new SystemMessage(system + "\n\n" + context));
    request.prompt().getInstructions().stream().filter(m -> !(m instanceof SystemMessage)).forEach(messages::add);
    return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
  }
  @Override public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) { return response; }
}
