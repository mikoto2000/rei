package dev.mikoto2000.rei.core.checkpoint;

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
 * 各ユーザーターンの LLM 呼び出し前に、最新 Checkpoint をコンテキストとして注入する。
 */
@Component
public class CheckpointAdvisor implements BaseAdvisor {

  private final CheckpointStore checkpointStore;
  private final AgentEventFactory events;
  private final AgentEventPublisher eventPublisher;

  public CheckpointAdvisor(CheckpointStore checkpointStore) {
    this(checkpointStore, (AgentEventFactory) null, (AgentEventPublisher) null);
  }

  @Autowired
  public CheckpointAdvisor(CheckpointStore checkpointStore, ObjectProvider<AgentEventFactory> events,
      ObjectProvider<AgentEventPublisher> eventPublisher) {
    this(checkpointStore, events.getIfAvailable(), eventPublisher.getIfAvailable());
  }

  public CheckpointAdvisor(CheckpointStore checkpointStore, AgentEventFactory events, AgentEventPublisher eventPublisher) {
    this.checkpointStore = checkpointStore;
    this.events = events;
    this.eventPublisher = eventPublisher;
  }

  CheckpointStore checkpointStore() {
    return checkpointStore;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String context = checkpointStore.renderForPrompt();
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
    return -65;
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
      eventPublisher.publish(events.contextInjected("checkpoint", null, context.length()));
    }
  }
}
