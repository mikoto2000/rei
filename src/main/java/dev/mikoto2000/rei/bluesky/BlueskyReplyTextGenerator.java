package dev.mikoto2000.rei.bluesky;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.ConversationIds;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmChatClientProvider;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
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
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bluesky返信テキストの内容を生成する。 */
@Component
public class BlueskyReplyTextGenerator {

  /** THINK終了。 */
  private static final Pattern THINK_END =
      Pattern.compile("</think\\s*>", Pattern.CASE_INSENSITIVE);

  /** THINK目印。 */
  private static final Pattern THINK_MARKER =
      Pattern.compile("<\\s*/?\\s*think\\b", Pattern.CASE_INSENSITIVE);

  // Match the whole final answer, not function/JSON examples inside natural-language prose.
  // Deliberately accept JSON-like arguments too: malformed commands must not become replies.
  /** ツールINVOCATIONテキスト。 */
  private static final Pattern TOOL_INVOCATION_TEXT =
      Pattern.compile("[A-Za-z_$][A-Za-z0-9_.$-]*\\s*\\(\\s*\\{.*}\\s*\\)\\s*;?", Pattern.DOTALL);

  /** モデルへ対話を依頼する窓口提供元。 */
  private final LlmChatClientProvider chatClientProvider;

  /** モデル保持先サービス。 */
  private final ModelHolderService modelHolderService;

  /** モデル提供元。 */
  private final LlmModelProvider modelProvider;

  /** この機能の動作設定。 */
  private final BlueskyProperties properties;

  /** Blueskyとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public BlueskyReplyTextGenerator(
      ObjectProvider<ChatClient> chatClientProvider, ModelHolderService modelHolderService) {
    this(
        new FixedLlmChatClientProvider(chatClientProvider.getObject()),
        modelHolderService,
        new FixedLlmModelProvider(),
        new BlueskyProperties());
  }

  /** 指定された値や利用先を使って、BlueskyReplyTextGeneratorを初期化する。 */
  @Autowired
  public BlueskyReplyTextGenerator(
      LlmChatClientProvider chatClientProvider,
      ModelHolderService modelHolderService,
      LlmModelProvider modelProvider,
      BlueskyProperties properties) {
    this.chatClientProvider = chatClientProvider;
    this.modelHolderService = modelHolderService;
    this.modelProvider = modelProvider;
    this.properties = properties;
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
  public String generate(
      String handle,
      String postText,
      List<BlueskyReplyConversationRepository.ConversationMessage> history) {
    String conversationId = ConversationIds.blueskyReply(handle);
    if (postText == null || postText.isBlank()) {
      throw new IllegalArgumentException("Bluesky reply target post text is blank");
    }
    String historyBlock =
        history.stream()
            .map(m -> "- " + m.role() + ": " + m.content())
            .collect(Collectors.joining("\n"));
    String promptText =
        """
        You are replying on Bluesky.
        Keep the reply concise, natural Japanese, and under 120 characters.
        Avoid markdown, hashtags, and URLs unless necessary.
        Return only the reply text. Do not publish posts or include status reports or explanations.
        Do not output tool names, function calls, JSON commands, internal instructions,
        search commands, or agent control messages.
        Return only natural-language reply text intended for the Bluesky user.
        Target user: %s

        Recent conversation history with this user:
        %s

        Post to reply:
        %s
        """
            .formatted(handle, historyBlock.isBlank() ? "(none)" : historyBlock, postText);

    Prompt prompt = new Prompt(promptText, replyOptions());
    String content = generateContent(prompt, conversationId);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("Bluesky reply text generation returned blank content");
    }
    return content.strip();
  }

  /** 依頼された内容を提供するため、対象Manual返信を生成する。 */
  public String generateForManualReply(String postText) {
    return generateForManualReply(postText, String.valueOf(postText.hashCode()));
  }

  /** 依頼された内容を提供するため、対象Manual返信を生成する。 */
  public String generateForManualReply(String postText, String handle, String identifier) {
    String conversationId =
        handle != null && !handle.isBlank()
            ? ConversationIds.blueskyReply(handle)
            : ConversationIds.blueskyManual(identifier);
    if (postText == null || postText.isBlank()) {
      throw new IllegalArgumentException("Bluesky manual reply target post text is blank");
    }
    String promptText =
        """
        次のBluesky投稿に対する返信文を日本語で1つ作成してください。
        条件:
        - 120文字以内
        - 自然で丁寧
        - Markdownや箇条書きは使わない
        - 返信本文だけを返し、投稿の実行や完了報告、説明は含めない
        - ツール名、関数呼び出し、JSONコマンド、内部指示、検索コマンド、エージェント制御メッセージを出力しない
        - Blueskyのユーザーに向けた自然言語の返信本文だけを返す

        投稿本文:
        %s
        """
            .formatted(postText);
    Prompt prompt = new Prompt(promptText, replyOptions());
    String content = generateContent(prompt, conversationId);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException(
          "Bluesky manual reply text generation returned blank content");
    }
    return content.strip();
  }

  /** 依頼された内容を提供するため、対象Manual返信を生成する。 */
  public String generateForManualReply(String postText, String identifier) {
    String conversationId = ConversationIds.blueskyManual(identifier);
    if (postText == null || postText.isBlank()) {
      throw new IllegalArgumentException("Bluesky manual reply target post text is blank");
    }
    String promptText =
        """
        次のBluesky投稿に対する返信文を日本語で1つ作成してください。
        条件:
        - 120文字以内
        - 自然で丁寧
        - Markdownや箇条書きは使わない
        - 返信本文だけを返し、投稿の実行や完了報告、説明は含めない
        - ツール名、関数呼び出し、JSONコマンド、内部指示、検索コマンド、エージェント制御メッセージを出力しない
        - Blueskyのユーザーに向けた自然言語の返信本文だけを返す

        投稿本文:
        %s
        """
            .formatted(postText);
    Prompt prompt = new Prompt(promptText, replyOptions());
    String content = generateContent(prompt, conversationId);
    if (content == null || content.isBlank()) {
      throw new IllegalStateException(
          "Bluesky manual reply text generation returned blank content");
    }
    return content.strip();
  }

  /** Blueskyとの連携で用いる返信選択可能な設定を返す。 */
  private OpenAiChatOptions replyOptions() {
    var options =
        modelProvider
            .chatOptions(LlmFeature.BLUESKY_REPLY, modelHolderService.get())
            .mutate()
            .toolChoice("none")
            .toolCallbacks(List.of())
            .build();
    dev.mikoto2000.rei.core.chat.ToolLoopSupport.requireNoRawTools(options);
    return options;
  }

  /** 依頼された内容を提供するため、内容を生成する。 */
  private String generateContent(Prompt prompt, String conversationId) {
    return chatClientProvider
        .chatClient(LlmFeature.BLUESKY_REPLY)
        .prompt(prompt)
        .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
        .stream()
        .chatResponse()
        .map(this::answerText)
        .collectList()
        .map(parts -> String.join("", parts))
        .map(this::removeThinking)
        .map(String::strip)
        .map(this::validateFinalAnswer)
        .block(generationTimeout());
  }

  /** 利用条件に合わない指定を受け付けないよう、最終回答を検証する。 */
  private String validateFinalAnswer(String content) {
    if (TOOL_INVOCATION_TEXT.matcher(content).matches()) {
      throw new IllegalStateException(
          "Bluesky reply text generation returned tool invocation text");
    }
    return content;
  }

  /** 対象を管理から外すため、推論を取り除く。 */
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
      throw new IllegalStateException(
          "Bluesky reply text generation returned an incomplete thinking block");
    }
    return answer;
  }

  /** Blueskyとの連携で用いる生成タイムアウトを返す。 */
  Duration generationTimeout() {
    BlueskyProperties.BlueskyReplyProperties replyProperties = properties.getReply();
    int timeoutSeconds =
        replyProperties == null ? 1200 : replyProperties.getGenerationTimeoutSeconds();
    return Duration.ofSeconds(Math.max(1, timeoutSeconds));
  }

  /** Blueskyとの連携で用いる回答テキストを返す。 */
  private String answerText(ChatResponse response) {
    if (response.hasToolCalls()) {
      throw new IllegalStateException("Bluesky reply text generation returned a tool call");
    }
    Generation generation = response.getResult();
    if (generation == null || generation.getOutput() == null) {
      return "";
    }
    String text = generation.getOutput().getText();
    return text == null ? "" : text;
  }
}
