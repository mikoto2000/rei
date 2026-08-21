package dev.mikoto2000.rei.core.stagnation;

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

/**
 * Stagnation 検出時に replan を要求する内部コンテキストを注入する。
 */
@Component
public class StagnationAdvisor implements BaseAdvisor {

  private final StagnationDetector stagnationDetector;

  public StagnationAdvisor(StagnationDetector stagnationDetector) {
    this.stagnationDetector = stagnationDetector;
  }

  StagnationDetector stagnationDetector() {
    return stagnationDetector;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    if (!stagnationDetector.isReplanRequested()) {
      return request;
    }
    String context = """
        ## Replan Notice

        現在の方針では進展していません。
        同じ操作を繰り返さず、原因を見直し、別の手順へ再計画してください。
        """;
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
    return -55;
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
}
