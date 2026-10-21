package dev.mikoto2000.rei.image;

import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 開始AI画像生成を外部の接続先へ依頼する窓口。 */
@Component
public class OpenAiImageGenerationClient implements ImageGenerationClient {

  /** 画像モデル提供元。 */
  private final ImageModelProvider imageModelProvider;

  /** この機能の動作設定。 */
  private final ImageProperties properties;

  /** 画像の生成を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public OpenAiImageGenerationClient(ImageModelProvider imageModelProvider) {
    this(imageModelProvider, new ImageProperties());
  }

  /** 指定された値や利用先を使って、OpenAiImageGenerationClientを初期化する。 */
  @Autowired
  public OpenAiImageGenerationClient(
      ImageModelProvider imageModelProvider, ImageProperties properties) {
    this.imageModelProvider = imageModelProvider;
    this.properties = properties;
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
  @Override
  public String generate(ImageGenerationRequest request) {
    ImageModel imageModel = imageModelProvider.imageModel();
    ImagePrompt prompt = buildPrompt(request);
    ImageResponse response = imageModel.call(prompt);
    if (response == null || response.getResult() == null) {
      throw new IllegalStateException("画像生成レスポンスが空です");
    }
    ImageGeneration generation = response.getResult();
    Image image = generation.getOutput();
    if (image == null || image.getB64Json() == null || image.getB64Json().isBlank()) {
      throw new IllegalStateException("画像データがレスポンスに含まれていません");
    }
    return image.getB64Json();
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
  ImagePrompt buildPrompt(ImageGenerationRequest request) {
    ImageOptionsBuilder options =
        ImageOptionsBuilder.builder()
            .n(1)
            .width(request.size().width())
            .height(request.size().height());
    String model = imageModelProvider.model(request.model());
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    if (shouldSendResponseFormat(model)) {
      options.responseFormat("b64_json");
    }
    // Portable options carry only requested overrides. SDK-specific immutable options also
    // inject model and timeout defaults, which would replace the configured model defaults.
    return new ImagePrompt(request.prompt(), options.build());
  }

  /** 画像の生成において、送信応答形式の実施条件を判定する。 */
  private boolean shouldSendResponseFormat(String model) {
    String responseFormat = properties.getResponseFormat();
    if (responseFormat == null
        || responseFormat.isBlank()
        || responseFormat.equalsIgnoreCase("auto")) {
      return model != null && !model.isBlank() && !isGptImageModel(model);
    }
    if (responseFormat.equalsIgnoreCase("none") || responseFormat.equalsIgnoreCase("off")) {
      return false;
    }
    if (responseFormat.equalsIgnoreCase("b64_json")) {
      return true;
    }
    throw new IllegalArgumentException("Unsupported rei.image.response-format: " + responseFormat);
  }

  /** 画像の生成において、GPT画像モデルかどうかを判定する。 */
  private boolean isGptImageModel(String model) {
    return model.toLowerCase(java.util.Locale.ROOT).startsWith("gpt-image-");
  }
}
