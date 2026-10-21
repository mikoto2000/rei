package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** 言語モデル話題候補の内容を生成する。 */
public class LlmTopicCandidateGenerator implements TopicCandidateGenerator {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(LlmTopicCandidateGenerator.class);

  /** 応答生成に使うモデル。 */
  private final ChatModel chatModel;

  /** モデル保持先サービス。 */
  private final ModelHolderService modelHolderService;

  /** 入力の解釈。 */
  private final TopicCandidateParser parser;

  /** この機能の動作設定。 */
  private final TopicGeneratorProperties properties;

  /** 指定された値や利用先を使って、LlmTopicCandidateGeneratorを初期化する。 */
  public LlmTopicCandidateGenerator(
      ChatModel chatModel,
      ModelHolderService modelHolderService,
      TopicCandidateParser parser,
      TopicGeneratorProperties properties) {
    this.chatModel = chatModel;
    this.modelHolderService = modelHolderService;
    this.parser = parser;
    this.properties = properties;
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
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

  /** 話題の管理で用いるプロンプトを返す。 */
  String prompt(TopicGenerationContext context) {
    String model = modelHolderService == null ? "" : modelHolderService.get();
    return """
        あなたは会話の話題候補を生成する補助コンポーネントです。

        ユーザーとの最近の会話および Working Set から、
        まだ完了していない作業、または後で確認した方がよい事項を探してください。

        この処理では発話そのものを生成しません。話題候補だけを生成してください。

        出力言語:
        - 入力の言語にかかわらず、topic と reason は必ず日本語で記述してください。
        - ファイル名、コマンド、コードの識別子、URL、固有名詞は原文のまま保持してください。
        - JSON のキーと type、source の列挙値は翻訳しないでください。

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
        {"candidates":[{"topic":"スクリプトの実行・テスト状況を確認する","reason":"最近の会話では実行結果がまだ確認できていないため","type":"unfinished_work","source":"working_set","priority":0.8,"freshness":0.9,"usefulness":0.8,"intrusiveness":0.2,"confidence":0.9}]}
        Max candidates: %d
        Model hint: %s

        Recent conversation:
        %s

        Working Set:
        %s

        Recent topics to avoid:
        %s
        """
        .formatted(
            properties.getMaxCandidates(),
            model,
            renderConversation(context),
            renderWorkingSet(context),
            String.join("\n", context.recentTopics()));
  }

  /** 利用者が内容を確認できるよう、会話を表示する。 */
  private String renderConversation(TopicGenerationContext context) {
    return context.recentConversation().stream()
        .map(message -> "- " + message.role() + ": " + bounded(message.content(), 500))
        .reduce((left, right) -> left + "\n" + right)
        .orElse("(none)");
  }

  /** 利用者が内容を確認できるよう、作業中集合を表示する。 */
  private String renderWorkingSet(TopicGenerationContext context) {
    return context.workingSet().stream()
        .map(item -> "- " + item.identifier() + " [" + item.kind() + "]")
        .reduce((left, right) -> left + "\n" + right)
        .orElse("(none)");
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private String bounded(String value, int maxLength) {
    if (value == null) return "";
    String safe = value.replaceAll("\\s+", " ").trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
  }
}
