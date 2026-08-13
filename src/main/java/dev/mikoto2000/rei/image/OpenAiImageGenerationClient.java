package dev.mikoto2000.rei.image;

import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.stereotype.Component;

@Component
public class OpenAiImageGenerationClient implements ImageGenerationClient {

  private final ImageModelProvider imageModelProvider;

  public OpenAiImageGenerationClient(ImageModelProvider imageModelProvider) {
    this.imageModelProvider = imageModelProvider;
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
        .responseFormat("b64_json")
        .width(request.size().width())
        .height(request.size().height());
    String model = imageModelProvider.model(request.model());
    if (model != null && !model.isBlank()) {
      options.model(model);
    }
    return new ImagePrompt(request.prompt(), options.build());
  }
}
