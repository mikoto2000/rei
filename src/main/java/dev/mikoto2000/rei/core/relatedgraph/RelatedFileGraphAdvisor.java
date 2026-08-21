package dev.mikoto2000.rei.core.relatedgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.working.WorkingSet;

/**
 * 各ユーザーターンの LLM 呼び出し前に、Working Set に関連する Related Files をコンテキストとして注入する。
 */
@Component
public class RelatedFileGraphAdvisor implements BaseAdvisor {

  private final RelatedFileGraph relatedFileGraph;
  private final WorkingSet workingSet;

  public RelatedFileGraphAdvisor(RelatedFileGraph relatedFileGraph, WorkingSet workingSet) {
    this.relatedFileGraph = relatedFileGraph;
    this.workingSet = workingSet;
  }

  RelatedFileGraph relatedFileGraph() {
    return relatedFileGraph;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    Set<String> workingSetPaths = workingSet.getFiles().stream()
        .map(ref -> ref.path())
        .collect(java.util.stream.Collectors.toSet());
    String context = relatedFileGraph.renderForPrompt(workingSetPaths);
    if (context.isBlank()) {
      return request;
    }
    String rendered = "## Related Files\n\n" + context;
    UserMessage contextualMessage = userMessage.mutate()
        .text(rendered + "\n\n" + userMessage.getText())
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
    return -50;
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
