package dev.mikoto2000.rei.image;

import java.nio.file.Path;

public record ImageGenerationResult(
    boolean success,
    Path savedPath,
    String message,
    String prompt) {

  public static ImageGenerationResult success(Path savedPath) {
    return success(savedPath, null);
  }

  public static ImageGenerationResult success(Path savedPath, String prompt) {
    return new ImageGenerationResult(true, savedPath, null, prompt);
  }

  public static ImageGenerationResult failure(String message) {
    return new ImageGenerationResult(false, null, message, null);
  }
}
