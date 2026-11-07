package dev.mikoto2000.rei.image;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/** 言語モデル画像プロンプトの内容を補強する。 */
@Component
public class LlmImagePromptEnhancer implements ImagePromptEnhancer {

  /** モデル提供元。 */
  private final Supplier<ChatModel> featureModel;

  private final Function<String, OpenAiChatOptions> optionsForModel;

  /** モデル保持先サービス。 */
  private final Supplier<String> readSelectedModel;

  /** 指定された値や利用先を使って、LlmImagePromptEnhancerを初期化する。 */
  public LlmImagePromptEnhancer(
      LlmModelProvider modelProvider, ModelHolderService modelHolderService) {
    this.featureModel = () -> modelProvider.chatModel(LlmFeature.IMAGE_PROMPT);
    this.optionsForModel = selected -> modelProvider.chatOptions(LlmFeature.IMAGE_PROMPT, selected);
    this.readSelectedModel = () -> modelHolderService.get();
  }

  /** 画像の生成で用いるenhanceを返す。 */
  @Override
  public String enhance(String userRequest) {
    Prompt prompt =
        new Prompt(
            buildPrompt(userRequest),
            optionsForModel.apply(readSelectedModel.get()));
    var response = featureModel.get().call(prompt);
    var generation = response == null ? null : response.getResult();
    var output = generation == null ? null : generation.getOutput();
    String content = output == null ? null : output.getText();
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("画像生成プロンプト生成結果が空です");
    }
    return content.strip();
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
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
        """
        + userRequest
        + "\n";
  }
}
