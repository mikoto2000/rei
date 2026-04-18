package dev.mikoto2000.rei.interest;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class LlmInterestTopicExtractor implements InterestTopicExtractor {

  private final ChatModel chatModel;
  private final ModelHolderService modelHolderService;
  private final JsonMapper objectMapper;

  @Override
  public List<InterestTopicCandidate> extract(List<ConversationSnippet> snippets, int maxTopics) {
    if (snippets.isEmpty()) {
      return List.of();
    }

    Prompt prompt = new Prompt(
        buildPrompt(snippets, maxTopics),
        OpenAiChatOptions.builder()
            .model(modelHolderService.get())
            .build());

    String response = chatModel.call(prompt).getResult().getOutput().getText();
    return parse(response);
  }

  private String buildPrompt(List<ConversationSnippet> snippets, int maxTopics) {
    String conversation = snippets.stream()
        .map(snippet -> "- [%s] %s".formatted(snippet.createdAt(), snippet.text()))
        .reduce((left, right) -> left + "\n" + right)
        .orElse("- なし");

    return """
        あなたはユーザーの過去会話から、今後 Web 検索して知らせる価値のある話題だけを抽出します。

        制約:
        - 出力は JSON 配列のみ
        - 最大 %d 件
        - 一時的な雑談、単発の依頼、あいさつは除外
        - private な内容や固有の個人情報は一般化
        - 各要素は topic, reason, searchQuery, score を持つ
        - score は 0.0 から 1.0
        - 該当がなければ [] を返す

        会話:
        %s
        """.formatted(maxTopics, conversation);
  }

  private List<InterestTopicCandidate> parse(String response) {
    try {
      return objectMapper.readValue(response, new TypeReference<List<InterestTopicCandidate>>() {
      });
    } catch (Exception e) {
      throw new IllegalStateException("興味トピック抽出結果の解析に失敗しました", e);
    }
  }
}
