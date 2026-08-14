package dev.mikoto2000.rei.image;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.core.service.CommandCancellationService;

@Service
public class ImageGenerationService {

  private final ImageGenerationClient client;
  private final ImageOutputPathResolver outputPathResolver;
  private final CommandCancellationService cancellationService;
  private final ImagePromptEnhancer promptEnhancer;
  private final ImageProperties properties;

  @Autowired
  public ImageGenerationService(ImageGenerationClient client, ImageProperties properties,
      CommandCancellationService cancellationService, ImagePromptEnhancer promptEnhancer) {
    this(client, new ImageOutputPathResolver(Path.of("").toAbsolutePath().normalize(), properties,
        java.time.Clock.systemDefaultZone()), cancellationService, promptEnhancer, properties);
  }

  ImageGenerationService(ImageGenerationClient client, ImageOutputPathResolver outputPathResolver) {
    this(client, outputPathResolver, null, new NoOpImagePromptEnhancer(), new ImageProperties());
  }

  ImageGenerationService(ImageGenerationClient client, ImageOutputPathResolver outputPathResolver,
      CommandCancellationService cancellationService) {
    this(client, outputPathResolver, cancellationService, new NoOpImagePromptEnhancer(), new ImageProperties());
  }

  ImageGenerationService(ImageGenerationClient client, ImageOutputPathResolver outputPathResolver,
      CommandCancellationService cancellationService, ImagePromptEnhancer promptEnhancer, ImageProperties properties) {
    this.client = client;
    this.outputPathResolver = outputPathResolver;
    this.cancellationService = cancellationService;
    this.promptEnhancer = promptEnhancer;
    this.properties = properties;
  }

  public ImageGenerationResult generate(ImageGenerationRequest request) {
    if (request.prompt() == null || request.prompt().isBlank()) {
      return ImageGenerationResult.failure("画像生成プロンプトを指定してください");
    }
    if (isCancellationRequested()) {
      return ImageGenerationResult.failure("cancelled");
    }
    try {
      ImageGenerationRequest effectiveRequest = effectiveRequest(request);
      if (isCancellationRequested()) {
        return ImageGenerationResult.failure("cancelled");
      }
      String base64 = client.generate(effectiveRequest);
      if (isCancellationRequested()) {
        return ImageGenerationResult.failure("cancelled");
      }
      if (base64 == null || base64.isBlank()) {
        return ImageGenerationResult.failure("画像データが空です");
      }
      byte[] bytes = Base64.getDecoder().decode(base64);
      Path outputPath = outputPathResolver.resolve(request.outputPath());
      if (outputPath.getParent() != null) {
        Files.createDirectories(outputPath.getParent());
      }
      Files.write(outputPath, bytes);
      return ImageGenerationResult.success(outputPath, effectiveRequest.prompt());
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

  private ImageGenerationRequest effectiveRequest(ImageGenerationRequest request) {
    if (!properties.isPromptEnhancementEnabled() || !request.enhancePrompt()) {
      return request;
    }
    String enhancedPrompt = promptEnhancer.enhance(request.prompt());
    if (enhancedPrompt == null || enhancedPrompt.isBlank()) {
      throw new IllegalStateException("画像生成プロンプト生成結果が空です");
    }
    return new ImageGenerationRequest(enhancedPrompt.strip(), request.outputPath(), request.model(), request.size(), false);
  }

  private boolean isCancellationRequested() {
    return Thread.currentThread().isInterrupted()
        || cancellationService != null && cancellationService.isCancellationRequested();
  }
}
