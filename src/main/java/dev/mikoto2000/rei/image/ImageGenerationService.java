package dev.mikoto2000.rei.image;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 画像生成のユースケースを実現する。 */
@Service
public class ImageGenerationService {
  /** 成果物一覧。 */
  private dev.mikoto2000.rei.artifact.ArtifactStore artifacts;

  /** 画像の生成で使用する成果物保存先を設定する。 */
  @Autowired(required = false)
  public void setArtifactStore(dev.mikoto2000.rei.artifact.ArtifactStore artifacts) {
    this.artifacts = artifacts;
  }

  /** クライアント。 */
  private final ImageGenerationClient client;

  /** 出力パス対象の解決。 */
  private final ImageOutputPathResolver outputPathResolver;

  /** キャンセルサービス。 */
  private final CommandCancellationService cancellationService;

  /** プロンプト内容の補強。 */
  private final ImagePromptEnhancer promptEnhancer;

  /** この機能の動作設定。 */
  private final ImageProperties properties;

  /** 画像の生成を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public ImageGenerationService(
      ImageGenerationClient client,
      ImageProperties properties,
      CommandCancellationService cancellationService,
      ImagePromptEnhancer promptEnhancer) {
    this(
        client,
        new ImageOutputPathResolver(
            Path.of("").toAbsolutePath().normalize(),
            properties,
            java.time.Clock.systemDefaultZone()),
        cancellationService,
        promptEnhancer,
        properties);
  }

  /** 画像の生成を利用するため、指定を補って共通の初期化へ引き渡す。 */
  ImageGenerationService(ImageGenerationClient client, ImageOutputPathResolver outputPathResolver) {
    this(client, outputPathResolver, null, new NoOpImagePromptEnhancer(), new ImageProperties());
  }

  /** 画像の生成を利用するため、指定を補って共通の初期化へ引き渡す。 */
  ImageGenerationService(
      ImageGenerationClient client,
      ImageOutputPathResolver outputPathResolver,
      CommandCancellationService cancellationService) {
    this(
        client,
        outputPathResolver,
        cancellationService,
        new NoOpImagePromptEnhancer(),
        new ImageProperties());
  }

  /** 指定された値や利用先を使って、ImageGenerationServiceを初期化する。 */
  ImageGenerationService(
      ImageGenerationClient client,
      ImageOutputPathResolver outputPathResolver,
      CommandCancellationService cancellationService,
      ImagePromptEnhancer promptEnhancer,
      ImageProperties properties) {
    this.client = client;
    this.outputPathResolver = outputPathResolver;
    this.cancellationService = cancellationService;
    this.promptEnhancer = promptEnhancer;
    this.properties = properties;
  }

  /** 指定された条件に従って、依頼された内容を生成する。 */
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
      String outputFilename =
          java.util.Objects.requireNonNull(outputPath.getFileName(), "Image filename required")
              .toString();
      var owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
      String artifact =
          artifacts != null && owner != null && owner.projectId() != null
              ? artifacts
                  .publish(
                      owner,
                      "image:"
                          + java.util.HexFormat.of()
                              .formatHex(
                                  java.security.MessageDigest.getInstance("SHA-256")
                                      .digest(
                                          outputFilename.getBytes(
                                              java.nio.charset.StandardCharsets.UTF_8))),
                      "image/png",
                      outputFilename,
                      bytes)
                  .artifactId()
              : null;
      return ImageGenerationResult.success(outputPath, effectiveRequest.prompt(), artifact);
    } catch (IllegalArgumentException e) {
      return ImageGenerationResult.failure("画像データのデコードに失敗しました");
    } catch (Exception e) {
      if (dev.mikoto2000.rei.core.chat.RunCancellation.isCancellation(e)
          || Thread.currentThread().isInterrupted())
        return ImageGenerationResult.failure("cancelled");
      return ImageGenerationResult.failure(sanitizeMessage(e));
    }
  }

  /** 利用先の制約に合う形式にするため、メッセージを整える。 */
  private String sanitizeMessage(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }

  /** 画像の生成で用いる実際に使う要求を返す。 */
  private ImageGenerationRequest effectiveRequest(ImageGenerationRequest request) {
    if (!properties.isPromptEnhancementEnabled() || !request.enhancePrompt()) {
      return request;
    }
    String enhancedPrompt = promptEnhancer.enhance(request.prompt());
    if (enhancedPrompt == null || enhancedPrompt.isBlank()) {
      throw new IllegalStateException("画像生成プロンプト生成結果が空です");
    }
    return new ImageGenerationRequest(
        enhancedPrompt.strip(), request.outputPath(), request.model(), request.size(), false);
  }

  /** 画像の生成において、キャンセル要求されたかどうかを判定する。 */
  private boolean isCancellationRequested() {
    return Thread.currentThread().isInterrupted()
        || cancellationService != null && cancellationService.isCancellationRequested();
  }
}
