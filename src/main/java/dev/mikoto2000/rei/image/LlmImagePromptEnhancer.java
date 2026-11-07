package dev.mikoto2000.rei.image;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import edu.umd.cs.findbugs.annotations.SuppressMatchType;
import java.util.function.Supplier;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** 言語モデル画像プロンプトの内容を補強する。 */
@Component
public class LlmImagePromptEnhancer implements ImagePromptEnhancer {

  /** モデル提供元。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      matchType = SuppressMatchType.EXACT,
      justification = "Uses the configured application model provider for model and option selection.")
  private final LlmModelProvider modelProvider;

  /** モデル保持先サービス。 */
  private final Supplier<String> readSelectedModel;

  /** 指定された値や利用先を使って、LlmImagePromptEnhancerを初期化する。 */
  public LlmImagePromptEnhancer(
      LlmModelProvider modelProvider, ModelHolderService modelHolderService) {
    this.modelProvider = modelProvider;
    this.readSelectedModel = () -> modelHolderService.get();
  }

  /** 画像の生成で用いるenhanceを返す。 */
  @Override
  public String enhance(String userRequest) {
    Prompt prompt =
        new Prompt(
            buildPrompt(userRequest),
            modelProvider.chatOptions(LlmFeature.IMAGE_PROMPT, readSelectedModel.get()));
    var response = modelProvider.chatModel(LlmFeature.IMAGE_PROMPT).call(prompt);
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
