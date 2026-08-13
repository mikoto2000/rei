package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ImageGenerationRecordsTest {

  @Test
  void requestHoldsGenerationInputs() {
    ImageSize size = new ImageSize(512, 512);
    Path output = Path.of("out.png");

    ImageGenerationRequest request = new ImageGenerationRequest("cat", output, "image-model", size);

    assertThat(request.prompt()).isEqualTo("cat");
    assertThat(request.outputPath()).isEqualTo(output);
    assertThat(request.model()).isEqualTo("image-model");
    assertThat(request.size()).isEqualTo(size);
  }

  @Test
  void resultHoldsSuccessAndFailureValues() {
    Path output = Path.of("out.png");

    assertThat(ImageGenerationResult.success(output).success()).isTrue();
    assertThat(ImageGenerationResult.success(output).savedPath()).isEqualTo(output);
    assertThat(ImageGenerationResult.failure("failed").success()).isFalse();
    assertThat(ImageGenerationResult.failure("failed").message()).isEqualTo("failed");
  }
}
