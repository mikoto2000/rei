package dev.mikoto2000.rei.image;

import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class OpenAiImageGenerationClient implements ImageGenerationClient {

  private final ImageModelProvider imageModelProvider;
  private final ImageProperties properties;

  public OpenAiImageGenerationClient(ImageModelProvider imageModelProvider) {
    this(imageModelProvider, new ImageProperties());
  }

  @Autowired
  public OpenAiImageGenerationClient(ImageModelProvider imageModelProvider, ImageProperties properties) {
    this.imageModelProvider = imageModelProvider;
    this.properties = properties;
  }

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

  ImagePrompt buildPrompt(ImageGenerationRequest request) {
    OpenAiImageOptions.Builder options = OpenAiImageOptions.builder()
        .N(1)
        .width(request.size().width())
        .height(request.size().height());
    String model = imageModelProvider.model(request.model());
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    if (shouldSendResponseFormat(model)) {
      options.responseFormat("b64_json");
    }
    return new ImagePrompt(request.prompt(), options.build());
  }

  private boolean shouldSendResponseFormat(String model) {
    String responseFormat = properties.getResponseFormat();
    if (responseFormat == null || responseFormat.isBlank() || responseFormat.equalsIgnoreCase("auto")) {
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

  private boolean isGptImageModel(String model) {
    return model.toLowerCase(java.util.Locale.ROOT).startsWith("gpt-image-");
  }
}
