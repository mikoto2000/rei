package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.core.service.CommandCancellationService;

class ImageGenerationServiceCancellationTest {

  @TempDir
  Path tempDir;

  @Test
  void doesNotSaveImageWhenCancellationWasRequestedAfterGeneration() throws Exception {
    CommandCancellationService cancellationService = new CommandCancellationService();
    cancellationService.begin(Thread.currentThread());
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir);
    ImageGenerationService service = new ImageGenerationService(request -> {
      cancellationService.cancel();
      return java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
    }, new ImageOutputPathResolver(tempDir, properties, Clock.systemDefaultZone()), cancellationService);
    Path output = tempDir.resolve("out.png");

    try {
      ImageGenerationResult result = service.generate(new ImageGenerationRequest("cat", output, null, new ImageSize(1, 1)));

      assertThat(result.success()).isFalse();
      assertThat(result.message()).isEqualTo("cancelled");
      assertThat(Files.exists(output)).isFalse();
    } finally {
      cancellationService.clear();
      Thread.interrupted();
    }
  }
}
