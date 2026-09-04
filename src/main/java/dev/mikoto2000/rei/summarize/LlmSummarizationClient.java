package dev.mikoto2000.rei.summarize;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;

@Component
public class LlmSummarizationClient implements SummarizationClient {

  private final LlmModelProvider modelProvider;
  private final ModelHolderService modelHolderService;

  public LlmSummarizationClient(LlmModelProvider modelProvider, ModelHolderService modelHolderService) {
    this.modelProvider = modelProvider;
    this.modelHolderService = modelHolderService;
  }

  @Override
  public String summarize(String content) {
    ChatModel model = modelProvider.chatModel(LlmFeature.WEB_PAGE_SUMMARY);
    ChatResponse response = model.call(new Prompt(
        new UserMessage(buildPrompt(content)),
        modelProvider.chatOptions(LlmFeature.WEB_PAGE_SUMMARY, modelHolderService.get(), false)));
    Generation generation = response == null ? null : response.getResult();
    if (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null) {
      return "";
    }
    return generation.getOutput().getText().strip();
  }

  private String buildPrompt(String content) {
    return """
        与えられた Web ページ本文を日本語で簡潔に要約してください。

        - ページの主要な内容を優先する
        - 広告、ナビゲーション、フッターなどは無視する
        - 原文に存在しない情報を追加しない
        - 重要なポイントを失わない

        Web ページ本文:
        %s
        """.formatted(content);
  }
}
