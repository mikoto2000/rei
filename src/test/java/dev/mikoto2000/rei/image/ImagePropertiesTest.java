package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImagePropertiesTest {

  @Test
  void defaultsResponseFormatToAuto() {
    ImageProperties properties = new ImageProperties();

    assertThat(properties.getResponseFormat()).isEqualTo("auto");
    assertThat(properties.getTimeoutSeconds()).isEqualTo(300);
  }
}
