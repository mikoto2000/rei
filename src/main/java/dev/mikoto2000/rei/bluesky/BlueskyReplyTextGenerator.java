package dev.mikoto2000.rei.bluesky;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.command.ChatCommand;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;

@Component
public class BlueskyReplyTextGenerator {

  private final LlmChatClientProvider chatClientProvider;
  private final ModelHolderService modelHolderService;
  private final LlmModelProvider modelProvider;

  public BlueskyReplyTextGenerator(ObjectProvider<ChatClient> chatClientProvider,
      ModelHolderService modelHolderService) {
    this(new ChatCommand.FixedLlmChatClientProvider(chatClientProvider.getObject()), modelHolderService,
        new ChatCommand.FixedLlmModelProvider());
  }

  @Autowired
  public BlueskyReplyTextGenerator(LlmChatClientProvider chatClientProvider, ModelHolderService modelHolderService,
      LlmModelProvider modelProvider) {
    this.chatClientProvider = chatClientProvider;
    this.modelHolderService = modelHolderService;
    this.modelProvider = modelProvider;
  }

  public String generate(String handle, String postText, List<BlueskyReplyConversationRepository.ConversationMessage> history) {
    if (postText == null || postText.isBlank()) {
      throw new IllegalArgumentException("Bluesky reply target post text is blank");
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

    Prompt prompt = new Prompt(promptText, OpenAiChatOptions.builder()
        .model(modelProvider.model(LlmFeature.BLUESKY_REPLY, modelHolderService.get()))
        .build());
    String content = generateContent(prompt);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("Bluesky reply text generation returned blank content");
    }
    return content.strip();
  }

  public String generateForManualReply(String postText) {
    if (postText == null || postText.isBlank()) {
      throw new IllegalArgumentException("Bluesky manual reply target post text is blank");
    }
    String promptText = """
        次のBluesky投稿に対する返信文を日本語で1つ作成してください。
        条件:
        - 120文字以内
        - 自然で丁寧
        - Markdownや箇条書きは使わない

        投稿本文:
        %s
        """.formatted(postText);
    Prompt prompt = new Prompt(promptText, OpenAiChatOptions.builder()
        .model(modelProvider.model(LlmFeature.BLUESKY_REPLY, modelHolderService.get()))
        .build());
    String content = generateContent(prompt);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("Bluesky manual reply text generation returned blank content");
    }
    return content.strip();
  }

  private String generateContent(Prompt prompt) {
    return chatClientProvider.chatClient(LlmFeature.BLUESKY_REPLY)
        .prompt(prompt)
        .stream()
        .chatResponse()
        .map(this::answerText)
        .collectList()
        .map(parts -> String.join("", parts))
        .block(Duration.ofMinutes(20));
  }

  private String answerText(ChatResponse response) {
    Generation generation = response.getResult();
    if (generation == null || generation.getOutput() == null) {
      return "";
    }
    String text = generation.getOutput().getText();
    return text == null ? "" : text;
  }
}
