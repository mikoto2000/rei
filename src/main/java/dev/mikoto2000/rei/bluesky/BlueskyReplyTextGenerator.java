package dev.mikoto2000.rei.bluesky;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;

@Component
public class BlueskyReplyTextGenerator {

  private static final Pattern THINK_END = Pattern.compile("</think\\s*>", Pattern.CASE_INSENSITIVE);
  private static final Pattern THINK_MARKER = Pattern.compile("<\\s*/?\\s*think\\b", Pattern.CASE_INSENSITIVE);

  private final LlmChatClientProvider chatClientProvider;
  private final ModelHolderService modelHolderService;
  private final LlmModelProvider modelProvider;
  private final BlueskyProperties properties;

  public BlueskyReplyTextGenerator(ObjectProvider<ChatClient> chatClientProvider,
      ModelHolderService modelHolderService) {
    this(new FixedLlmChatClientProvider(chatClientProvider.getObject()), modelHolderService,
        new FixedLlmModelProvider(), new BlueskyProperties());
  }

  @Autowired
  public BlueskyReplyTextGenerator(LlmChatClientProvider chatClientProvider, ModelHolderService modelHolderService,
      LlmModelProvider modelProvider, BlueskyProperties properties) {
    this.chatClientProvider = chatClientProvider;
    this.modelHolderService = modelHolderService;
    this.modelProvider = modelProvider;
    this.properties = properties;
  }

  public String generate(String handle, String postText, List<BlueskyReplyConversationRepository.ConversationMessage> history) {
    String conversationId = ConversationIds.blueskyReply(handle);
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

    Prompt prompt = new Prompt(promptText,
        modelProvider.chatOptions(LlmFeature.BLUESKY_REPLY, modelHolderService.get()));
    String content = generateContent(prompt, conversationId);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("Bluesky reply text generation returned blank content");
    }
    return content.strip();
  }

  public String generateForManualReply(String postText) {
    return generateForManualReply(postText, String.valueOf(postText.hashCode()));
  }

  public String generateForManualReply(String postText, String handle, String identifier) {
    String conversationId = handle != null && !handle.isBlank()
        ? ConversationIds.blueskyReply(handle)
        : ConversationIds.blueskyManual(identifier);
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
    Prompt prompt = new Prompt(promptText,
        modelProvider.chatOptions(LlmFeature.BLUESKY_REPLY, modelHolderService.get()));
    String content = generateContent(prompt, conversationId);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("Bluesky manual reply text generation returned blank content");
    }
    return content.strip();
  }

  public String generateForManualReply(String postText, String identifier) {
    String conversationId = ConversationIds.blueskyManual(identifier);
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
    Prompt prompt = new Prompt(promptText,
        modelProvider.chatOptions(LlmFeature.BLUESKY_REPLY, modelHolderService.get()));
    String content = generateContent(prompt, conversationId);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("Bluesky manual reply text generation returned blank content");
    }
    return content.strip();
  }

  private String generateContent(Prompt prompt, String conversationId) {
    return chatClientProvider.chatClient(LlmFeature.BLUESKY_REPLY)
        .prompt(prompt)
        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
        .stream()
        .chatResponse()
        .map(this::answerText)
        .collectList()
        .map(parts -> String.join("", parts))
        .map(this::removeThinking)
        .block(generationTimeout());
  }

  private String removeThinking(String content) {
    // Some providers omit <think>, leaving reasoning (and draft answers) before </think>.
    // Inspect the complete response so even tags split across stream chunks are handled.
    Matcher end = THINK_END.matcher(content);
    int answerStart = 0;
    while (end.find()) {
      answerStart = end.end();
    }
    String answer = content.substring(answerStart);
    if (THINK_MARKER.matcher(answer).find()) {
      throw new IllegalStateException("Bluesky reply text generation returned an incomplete thinking block");
    }
    return answer;
  }

  Duration generationTimeout() {
    BlueskyProperties.BlueskyReplyProperties replyProperties = properties.getReply();
    int timeoutSeconds = replyProperties == null ? 1200 : replyProperties.getGenerationTimeoutSeconds();
    return Duration.ofSeconds(Math.max(1, timeoutSeconds));
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
