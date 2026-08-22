package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;

@Component
public class AgentSkillAdvisor implements BaseAdvisor {

  private final AgentSkillSelectionService selectionService;
  private final AgentSkillPromptRenderer promptRenderer;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;

  public AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer) {
    this(selectionService, promptRenderer, new AgentEventFactory(java.time.Clock.systemDefaultZone()),
        new InMemoryAgentEventBus());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer,
      AgentEventFactory eventFactory, AgentEventPublisher eventPublisher) {
    this.selectionService = selectionService;
    this.promptRenderer = promptRenderer;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    Prompt prompt = request.prompt();
    UserMessage userMessage = prompt.getUserMessage();
    if (userMessage == null) {
      return request;
    }

    String selectionId = UUID.randomUUID().toString();
    eventPublisher.publish(eventFactory.skillSelectionStarted(selectionId));
    AgentSkillSelection selection;
    try {
      selection = selectionService.select(userMessage.getText());
      eventPublisher.publish(eventFactory.skillSelectionCompleted(selectionId,
          skillNames(selection.explicitSkills()), skillNames(selection.implicitSkills()), selection.warnings()));
    } catch (RuntimeException exception) {
      eventPublisher.publish(eventFactory.skillSelectionFailed(selectionId, ErrorInformation.from(exception)));
      throw exception;
    }
    if (selection.selectedSkills().isEmpty() && selection.sanitizedPrompt().equals(userMessage.getText())) {
      return request;
    }

    String renderedText = promptRenderer.render(selection.sanitizedPrompt(), selection.selectedSkills());
    UserMessage renderedUserMessage = userMessage.mutate()
        .text(renderedText)
        .build();

    Prompt renderedPrompt = new Prompt(replaceUserMessage(prompt.getInstructions(), userMessage, renderedUserMessage),
        prompt.getOptions());
    return request.mutate()
        .prompt(renderedPrompt)
        .build();
  }

  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  @Override
  public int getOrder() {
    return 0;
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

  private List<String> skillNames(List<AgentSkill> skills) {
    return skills.stream()
        .map(AgentSkill::name)
        .toList();
  }
}
