package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ImageSizeTest {

  @Test
  void parsesWidthAndHeight() {
    ImageSize size = ImageSize.parse("1024x768");

    assertThat(size.width()).isEqualTo(1024);
    assertThat(size.height()).isEqualTo(768);
    assertThat(size.toString()).isEqualTo("1024x768");
  }

  @Test
  void rejectsInvalidFormat() {
    assertThatThrownBy(() -> ImageSize.parse("1024")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("1024*1024")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("0x1024")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("1024x0")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImageSize.parse("-1x1024")).isInstanceOf(IllegalArgumentException.class);
  }
}
