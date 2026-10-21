package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 画像設定の結果と状態の契約を検証するテスト。 */
class ImagePropertiesTest {

  /** 画像の生成で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void defaultsResponseFormatToAuto() {
    ImageProperties properties = new ImageProperties();

    assertThat(properties.getResponseFormat()).isEqualTo("auto");
    assertThat(properties.getTimeoutSeconds()).isEqualTo(300);
    assertThat(properties.isPromptEnhancementEnabled()).isTrue();
  }
}
