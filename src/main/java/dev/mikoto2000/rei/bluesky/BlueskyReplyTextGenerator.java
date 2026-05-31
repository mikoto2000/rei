package dev.mikoto2000.rei.bluesky;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BlueskyReplyTextGenerator {

  private final ChatModel chatModel;
  private final ModelHolderService modelHolderService;

  public String generate(String handle, String postText, List<BlueskyReplyConversationRepository.ConversationMessage> history) {
    if (postText == null || postText.isBlank()) {
      return "Thanks for your post.";
    }
    String historyBlock = history.stream()
        .map(m -> "- " + m.role() + ": " + m.content())
        .collect(Collectors.joining("\n"));
    String promptText = """
        You are replying on Bluesky.
        Keep the reply concise, natural Japanese, and under 120 characters.
        Avoid markdown, hashtags, and URLs unless necessary.
        Target user: %s

        Recent conversation history with this user:
        %s

        Post to reply:
        %s
        """.formatted(handle, historyBlock.isBlank() ? "(none)" : historyBlock, postText);

    Prompt prompt = new Prompt(promptText, OpenAiChatOptions.builder().model(modelHolderService.get()).build());
    String content = chatModel.call(prompt).getResult().getOutput().getText();
    if (content == null || content.isBlank()) {
      return "Thanks for sharing.";
    }
    return content.strip();
  }
}
