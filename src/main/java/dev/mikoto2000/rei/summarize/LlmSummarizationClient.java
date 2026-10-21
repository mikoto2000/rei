package dev.mikoto2000.rei.summarize;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** 言語モデルSummarizationを外部の接続先へ依頼する窓口。 */
@Component
public class LlmSummarizationClient implements SummarizationClient {

  /** モデル提供元。 */
  private final LlmModelProvider modelProvider;

  /** モデル保持先サービス。 */
  private final ModelHolderService modelHolderService;

  /** 指定された値や利用先を使って、LlmSummarizationClientを初期化する。 */
  public LlmSummarizationClient(
      LlmModelProvider modelProvider, ModelHolderService modelHolderService) {
    this.modelProvider = modelProvider;
    this.modelHolderService = modelHolderService;
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  @Override
  public String summarize(String content) {
    ChatModel model = modelProvider.chatModel(LlmFeature.WEB_PAGE_SUMMARY);
    ChatResponse response =
        model.call(
            new Prompt(
                new UserMessage(buildPrompt(content)),
                modelProvider.chatOptions(
                    LlmFeature.WEB_PAGE_SUMMARY, modelHolderService.get(), false)));
    Generation generation = response == null ? null : response.getResult();
    if (generation == null
        || generation.getOutput() == null
        || generation.getOutput().getText() == null) {
      return "";
    }
    return generation.getOutput().getText().strip();
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
  private String buildPrompt(String content) {
    return """
        与えられた Web ページ本文を日本語で簡潔に要約してください。

        - ページの主要な内容を優先する
        - 広告、ナビゲーション、フッターなどは無視する
        - 原文に存在しない情報を追加しない
        - 重要なポイントを失わない

        Web ページ本文:
        %s
        """
        .formatted(content);
  }
}
