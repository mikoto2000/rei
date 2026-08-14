package dev.mikoto2000.rei.image;

import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;

@Component
public class LlmImagePromptEnhancer implements ImagePromptEnhancer {

  private final LlmModelProvider modelProvider;
  private final ModelHolderService modelHolderService;

  public LlmImagePromptEnhancer(LlmModelProvider modelProvider, ModelHolderService modelHolderService) {
    this.modelProvider = modelProvider;
    this.modelHolderService = modelHolderService;
  }

  @Override
  public String enhance(String userRequest) {
    Prompt prompt = new Prompt(
        buildPrompt(userRequest),
        OpenAiChatOptions.builder()
            .model(modelProvider.model(LlmFeature.IMAGE_PROMPT, modelHolderService.get()))
            .build());
    String content = modelProvider.chatModel(LlmFeature.IMAGE_PROMPT).call(prompt).getResult().getOutput().getText();
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("画像生成プロンプト生成結果が空です");
    }
    return content.strip();
  }

  private String buildPrompt(String userRequest) {
    return """
        You are an expert prompt writer for image generation models.
        Rewrite the user's request into a single high-quality image generation prompt.

        Rules:
        - Output only the final image prompt.
        - Do not include explanations, markdown, code fences, or labels.
        - Preserve the user's intent and important constraints.
        - Add concise visual details such as subject, composition, style, lighting, mood, and background when useful.
        - If the user wrote Japanese, the output may be English unless the image requires Japanese text.
        - Do not add unsafe or policy-violating content.

        User request:
        %s
        """.formatted(userRequest);
  }
}
