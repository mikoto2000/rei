package dev.mikoto2000.rei.image;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ImageGenerationService {

  private final ImageGenerationClient client;
  private final ImageOutputPathResolver outputPathResolver;

  @Autowired
  public ImageGenerationService(ImageGenerationClient client, ImageProperties properties) {
    this(client, new ImageOutputPathResolver(Path.of("").toAbsolutePath().normalize(), properties,
        java.time.Clock.systemDefaultZone()));
  }

  ImageGenerationService(ImageGenerationClient client, ImageOutputPathResolver outputPathResolver) {
    this.client = client;
    this.outputPathResolver = outputPathResolver;
  }

  public ImageGenerationResult generate(ImageGenerationRequest request) {
    if (request.prompt() == null || request.prompt().isBlank()) {
      return ImageGenerationResult.failure("画像生成プロンプトを指定してください");
    }
    try {
      String base64 = client.generate(request);
      if (base64 == null || base64.isBlank()) {
        return ImageGenerationResult.failure("画像データが空です");
      }
      byte[] bytes = Base64.getDecoder().decode(base64);
      Path outputPath = outputPathResolver.resolve(request.outputPath());
      if (outputPath.getParent() != null) {
        Files.createDirectories(outputPath.getParent());
      }
      Files.write(outputPath, bytes);
      return ImageGenerationResult.success(outputPath);
    } catch (IllegalArgumentException e) {
      return ImageGenerationResult.failure("画像データのデコードに失敗しました");
    } catch (Exception e) {
      return ImageGenerationResult.failure(sanitizeMessage(e));
    }
  }

  private String sanitizeMessage(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }
}
