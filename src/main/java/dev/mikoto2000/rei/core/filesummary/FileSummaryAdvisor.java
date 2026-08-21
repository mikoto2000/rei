package dev.mikoto2000.rei.core.filesummary;

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
 * 各ユーザーターンの LLM 呼び出し前に、有効な File Summary をコンテキストとして注入する。
 */
@Component
public class FileSummaryAdvisor implements BaseAdvisor {

  private final FileSummaryCache fileSummaryCache;

  public FileSummaryAdvisor(FileSummaryCache fileSummaryCache) {
    this.fileSummaryCache = fileSummaryCache;
  }

  FileSummaryCache fileSummaryCache() {
    return fileSummaryCache;
  }

  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    String context = fileSummaryCache.renderForPrompt(path -> currentVersion(path));
    if (context.isBlank()) {
      return request;
    }
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
    return -60;
  }

  private String currentVersion(String path) {
    try {
      java.nio.file.Path p = java.nio.file.Paths.get(path);
      if (!java.nio.file.Files.exists(p)) {
        return null;
      }
      return sha256(p);
    } catch (Exception e) {
      return null;
    }
  }

  private String sha256(java.nio.file.Path path) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    byte[] bytes = java.nio.file.Files.readAllBytes(path);
    byte[] hash = digest.digest(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : hash) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
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
