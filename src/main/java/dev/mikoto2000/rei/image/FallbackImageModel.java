package dev.mikoto2000.rei.image;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageMessage;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;

class FallbackImageModel implements ImageModel {

  private static final Logger log = LoggerFactory.getLogger(FallbackImageModel.class);

  private final String feature;
  private final ImageModel primary;
  private final ImageModel fallback;
  private final String primaryModel;

  FallbackImageModel(String feature, ImageModel primary, ImageModel fallback, String primaryModel) {
    this.feature = feature;
    this.primary = primary;
    this.fallback = fallback;
    this.primaryModel = primaryModel;
  }

  @Override
  public ImageResponse call(ImagePrompt prompt) {
    try {
      return primary.call(prompt);
    } catch (RuntimeException e) {
      log.warn("Feature image model call failed. Falling back to default image model: feature={}", feature, e);
      return fallback.call(fallbackPrompt(prompt));
    }
  }

  private ImagePrompt fallbackPrompt(ImagePrompt prompt) {
    ImageOptions options = prompt.getOptions();
    if (!(options instanceof OpenAiImageOptions openAiOptions)
        || primaryModel == null
        || primaryModel.isBlank()
        || !primaryModel.equals(openAiOptions.getModel())) {
      return prompt;
    }
    OpenAiImageOptions fallbackOptions = openAiOptions.copy();
    fallbackOptions.setModel(null);
    List<ImageMessage> instructions = prompt.getInstructions();
    return new ImagePrompt(instructions, fallbackOptions);
  }
}
