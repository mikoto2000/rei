package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 画像生成サービスキャンセルの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ImageGenerationServiceCancellationTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 画像の生成の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void doesNotSaveImageWhenCancellationWasRequestedAfterGeneration() throws Exception {
    CommandCancellationService cancellationService = new CommandCancellationService();
    cancellationService.begin(Thread.currentThread());
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir);
    ImageGenerationService service =
        new ImageGenerationService(
            request -> {
              cancellationService.cancel();
              return java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
            },
            new ImageOutputPathResolver(tempDir, properties, Clock.systemDefaultZone()),
            cancellationService);
    Path output = tempDir.resolve("out.png");

    try {
      ImageGenerationResult result =
          service.generate(new ImageGenerationRequest("cat", output, null, new ImageSize(1, 1)));

      assertThat(result.success()).isFalse();
      assertThat(result.message()).isEqualTo("cancelled");
      assertThat(Files.exists(output)).isFalse();
    } finally {
      cancellationService.clear();
      Thread.interrupted();
    }
  }
}
