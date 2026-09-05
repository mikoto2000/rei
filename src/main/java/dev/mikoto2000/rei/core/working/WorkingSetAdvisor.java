package dev.mikoto2000.rei.core.working;

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
 * 各ユーザーターンの LLM 呼び出し前に、現在の Working Set をコンテキストとして注入する。
 */
@Component
public class WorkingSetAdvisor implements BaseAdvisor {

  private final WorkingSet workingSet;
  private final AgentEventFactory events;
  private final AgentEventPublisher eventPublisher;

  public WorkingSetAdvisor(WorkingSet workingSet) {
    this(workingSet, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  @Autowired
  public WorkingSetAdvisor(WorkingSet workingSet, ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(workingSet, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  public WorkingSetAdvisor(WorkingSet workingSet, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.workingSet = workingSet;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String workingSetContext = workingSet.renderForPrompt();
    if (workingSetContext.isBlank()) {
      return request;
    }
    publishContextInjected(workingSetContext);
    UserMessage contextualMessage = userMessage.mutate()
        .text(workingSetContext + "\n\n" + userMessage.getText())
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
    return -90;
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

  private void publishContextInjected(String workingSetContext) {
    if (events == null || eventPublisher == null) {
      return;
    }
    eventPublisher.publish(events.workingSetContextInjected(workingSet.getFiles().size(), workingSetContext.length()));
  }
}
