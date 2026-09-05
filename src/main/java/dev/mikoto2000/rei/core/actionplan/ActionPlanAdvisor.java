package dev.mikoto2000.rei.core.actionplan;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

/**
 * 各ユーザーターンの LLM 呼び出し前に、現在の Action Plan をコンテキストとして注入する。
 */
@Component
public class ActionPlanAdvisor implements BaseAdvisor {

  private final ActionPlan actionPlan;
  private final AgentEventFactory events;
  private final AgentEventPublisher eventPublisher;

  public ActionPlanAdvisor(ActionPlan actionPlan) {
    this(actionPlan, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  @Autowired
  public ActionPlanAdvisor(ActionPlan actionPlan, ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(actionPlan, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  public ActionPlanAdvisor(ActionPlan actionPlan, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.actionPlan = actionPlan;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  ActionPlan actionPlan() {
    return actionPlan;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String context = actionPlan.renderForPrompt();
    if (context.isBlank()) {
      return request;
    }
    publishInjected(context);
    UserMessage contextualMessage = userMessage.mutate()
        .text(context + "\n\n" + userMessage.getText())
        .build();
    Prompt prompt = request.prompt();
    return request.mutate()
        .prompt(new Prompt(replaceUserMessage(prompt.getInstructions(), userMessage, contextualMessage),
            prompt.getOptions()))
        .build();
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return -75;
  }

  private List<Message> replaceUserMessage(List<Message> messages, UserMessage original, UserMessage replacement) {
    List<Message> replaced = new ArrayList<>(messages.size());
    boolean replacedFirst = false;
    for (Message message : messages) {
      if (!replacedFirst && message == original) {
        replaced.add(replacement);
        replacedFirst = true;
      } else {
        replaced.add(message);
      }
    }
    return List.copyOf(replaced);
  }

  private void publishInjected(String context) {
    if (events != null && eventPublisher != null) {
      eventPublisher.publish(events.contextInjected("action_plan", null, context.length()));
    }
  }
}
