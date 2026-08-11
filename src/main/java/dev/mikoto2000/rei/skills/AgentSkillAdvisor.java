package dev.mikoto2000.rei.skills;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class AgentSkillAdvisor implements BaseAdvisor {

  private final AgentSkillSelectionService selectionService;
  private final AgentSkillPromptRenderer promptRenderer;

  public AgentSkillAdvisor(AgentSkillSelectionService selectionService, AgentSkillPromptRenderer promptRenderer) {
    this.selectionService = selectionService;
    this.promptRenderer = promptRenderer;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    Prompt prompt = request.prompt();
    UserMessage userMessage = prompt.getUserMessage();
    if (userMessage == null) {
      return request;
    }

    AgentSkillSelection selection = selectionService.select(userMessage.getText());
    printWarnings(selection);
    printSelectedSkills(selection);
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

  private void printWarnings(AgentSkillSelection selection) {
    for (String warning : selection.warnings()) {
      System.out.println(warning);
    }
  }

  private void printSelectedSkills(AgentSkillSelection selection) {
    List<String> skillNames = selection.selectedSkills().stream()
        .map(AgentSkill::name)
        .toList();
    if (!skillNames.isEmpty()) {
      System.out.println("実行スキル: " + String.join(", ", skillNames));
    }
  }
}
