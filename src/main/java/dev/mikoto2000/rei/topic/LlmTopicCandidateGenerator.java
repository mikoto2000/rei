package dev.mikoto2000.rei.topic;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.ModelHolderService;

public class LlmTopicCandidateGenerator implements TopicCandidateGenerator {
  private static final Logger log = LoggerFactory.getLogger(LlmTopicCandidateGenerator.class);

  private final ChatModel chatModel;
  private final ModelHolderService modelHolderService;
  private final TopicCandidateParser parser;
  private final TopicGeneratorProperties properties;

  public LlmTopicCandidateGenerator(ChatModel chatModel, ModelHolderService modelHolderService,
      TopicCandidateParser parser, TopicGeneratorProperties properties) {
    this.chatModel = chatModel;
    this.modelHolderService = modelHolderService;
    this.parser = parser;
    this.properties = properties;
  }

  @Override
  public List<TopicCandidate> generate(TopicGenerationContext context) {
    if (!properties.isEnabled()) return List.of();
    try {
      ChatResponse response = chatModel.call(new Prompt(prompt(context)));
      AssistantMessage output = response.getResult().getOutput();
      return parser.parse(output == null ? "" : output.getText(), properties.getMaxCandidates());
    } catch (Exception e) {
      log.warn("Topic candidate generation failed");
      return List.of();
    }
  }

  String prompt(TopicGenerationContext context) {
    String model = modelHolderService == null ? "" : modelHolderService.get();
    return """
        あなたは会話の話題候補を生成する補助コンポーネントです。

        ユーザーとの最近の会話および Working Set から、
        まだ完了していない作業、または後で確認した方がよい事項を探してください。

        この処理では発話そのものを生成しません。話題候補だけを生成してください。

        対象 type:
        - unfinished_work
        - follow_up

        次のものは候補にしないでください。
        - 完了済みの作業
        - 既に直近で話題にした内容
        - 根拠のない推測
        - ユーザーに価値が薄い雑談
        - 単なる過去情報の繰り返し

        Output must be a JSON object only:
        {"candidates":[{"topic":"...","reason":"...","type":"unfinished_work","source":"working_set","priority":0.8,"freshness":0.9,"usefulness":0.8,"intrusiveness":0.2,"confidence":0.9}]}
        Max candidates: %d
        Model hint: %s

        Recent conversation:
        %s

        Working Set:
        %s

        Recent topics to avoid:
        %s
        """.formatted(properties.getMaxCandidates(), model, renderConversation(context), renderWorkingSet(context),
        String.join("\n", context.recentTopics()));
  }

  private String renderConversation(TopicGenerationContext context) {
    return context.recentConversation().stream()
        .map(message -> "- " + message.role() + ": " + bounded(message.content(), 500))
        .reduce((left, right) -> left + "\n" + right)
        .orElse("(none)");
  }

  private String renderWorkingSet(TopicGenerationContext context) {
    return context.workingSet().stream()
        .map(item -> "- " + item.identifier() + " [" + item.kind() + "]")
        .reduce((left, right) -> left + "\n" + right)
        .orElse("(none)");
  }

  private String bounded(String value, int maxLength) {
    if (value == null) return "";
    String safe = value.replaceAll("\\s+", " ").trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
  }
}
