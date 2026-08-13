package dev.mikoto2000.rei.image;

import java.nio.file.Path;

public record ImageGenerationResult(
    boolean success,
    Path savedPath,
    String message) {

  public static ImageGenerationResult success(Path savedPath) {
    return new ImageGenerationResult(true, savedPath, null);
  }

  public static ImageGenerationResult failure(String message) {
    return new ImageGenerationResult(false, null, message);
  }
}
