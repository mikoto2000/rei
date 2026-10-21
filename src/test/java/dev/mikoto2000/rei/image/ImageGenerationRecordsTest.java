package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 画像生成記録一覧の結果と状態の契約を検証するテスト。 */
class ImageGenerationRecordsTest {

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void requestHoldsGenerationInputs() {
    ImageSize size = new ImageSize(512, 512);
    Path output = Path.of("out.png");

    ImageGenerationRequest request = new ImageGenerationRequest("cat", output, "image-model", size);

    assertThat(request.prompt()).isEqualTo("cat");
    assertThat(request.outputPath()).isEqualTo(output);
    assertThat(request.model()).isEqualTo("image-model");
    assertThat(request.size()).isEqualTo(size);
    assertThat(request.enhancePrompt()).isTrue();
  }

  /** 画像の生成で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void resultHoldsSuccessAndFailureValues() {
    Path output = Path.of("out.png");

    assertThat(ImageGenerationResult.success(output).success()).isTrue();
    assertThat(ImageGenerationResult.success(output).savedPath()).isEqualTo(output);
    assertThat(ImageGenerationResult.success(output, "prompt").prompt()).isEqualTo("prompt");
    assertThat(ImageGenerationResult.failure("failed").success()).isFalse();
    assertThat(ImageGenerationResult.failure("failed").message()).isEqualTo("failed");
  }
}
