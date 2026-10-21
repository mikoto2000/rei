package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 画像大きさの結果と状態の契約を検証するテスト。 */
class ImageSizeTest {

  /** 画像の生成で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parsesWidthAndHeight() {
    ImageSize size = ImageSize.parse("1024x768");

    assertThat(size.width()).isEqualTo(1024);
    assertThat(size.height()).isEqualTo(768);
    assertThat(size.toString()).isEqualTo("1024x768");
  }

  /** 不正な形式を受け付けないことを確認する。 */
  @Test
  void rejectsInvalidFormat() {
    assertThatThrownBy(() -> ImageSize.parse("1024")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("1024*1024"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("0x1024"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("1024x0"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("-1x1024"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
