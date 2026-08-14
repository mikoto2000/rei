package dev.mikoto2000.rei.image;

import java.nio.file.Path;

public record ImageGenerationRequest(
    String prompt,
    Path outputPath,
    String model,
    ImageSize size,
    boolean enhancePrompt) {

  public ImageGenerationRequest(String prompt, Path outputPath, String model, ImageSize size) {
    this(prompt, outputPath, model, size, true);
  }
}
