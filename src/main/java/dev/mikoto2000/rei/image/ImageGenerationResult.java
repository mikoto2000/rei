package dev.mikoto2000.rei.image;

import java.nio.file.Path;

public record ImageGenerationResult(
    boolean success,
    Path savedPath,
    String message,
    String prompt,
    String artifactId) {
  public ImageGenerationResult(boolean success,Path savedPath,String message,String prompt){this(success,savedPath,message,prompt,null);}
  public static ImageGenerationResult success(Path savedPath,String prompt,String artifactId){return new ImageGenerationResult(true,savedPath,null,prompt,artifactId);}

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
