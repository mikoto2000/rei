package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;

/** 代替手段画像モデルの結果と状態の契約を検証するテスト。 */
class FallbackImageModelTest {

  /** 共通環境向け要求選択可能な設定について、設定済み代替手段モデルを利用することを確認する。 */
  @Test
  void portableRequestOptionsUseTheConfiguredFallbackModel() {
    var primary = Mockito.mock(ImageModel.class);
    var fallback = Mockito.mock(org.springframework.ai.openai.OpenAiImageModel.class);
    when(fallback.getOptions())
        .thenReturn(OpenAiImageOptions.builder().model("local-image-fallback").build());
    var prompt =
        new ImagePrompt(
            "cat",
            org.springframework.ai.image.ImageOptionsBuilder.builder()
                .model("feature-model")
                .width(512)
                .height(512)
                .responseFormat("b64_json")
                .build());
    when(primary.call(prompt)).thenThrow(new RuntimeException("unavailable"));
    when(fallback.call(Mockito.any(ImagePrompt.class))).thenReturn(response("image"));

    new FallbackImageModel("image-generation", primary, fallback, "feature-model").call(prompt);

    var captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(fallback).call(captor.capture());
    assertThat(captor.getValue().getOptions().getModel()).isEqualTo("local-image-fallback");
    assertThat(captor.getValue().getOptions().getWidth()).isEqualTo(512);
    assertThat(captor.getValue().getOptions().getResponseFormat()).isEqualTo("b64_json");
  }

  /** 代替手段について、設定済みモデルを利用することを確認する。 */
  @Test
  void fallbackUsesConfiguredModelAndPreservesImageOptions() {
    var primary = Mockito.mock(ImageModel.class);
    var fallback = Mockito.mock(org.springframework.ai.openai.OpenAiImageModel.class);
    when(fallback.getOptions())
        .thenReturn(OpenAiImageOptions.builder().model("local-image-fallback").build());
    var prompt =
        new ImagePrompt(
            "cat",
            OpenAiImageOptions.builder()
                .model("feature-model")
                .width(512)
                .height(512)
                .quality("high")
                .responseFormat("b64_json")
                .build());
    when(primary.call(prompt)).thenThrow(new RuntimeException("unavailable"));
    when(fallback.call(Mockito.any(ImagePrompt.class))).thenReturn(response("image"));

    new FallbackImageModel("image-generation", primary, fallback, "feature-model").call(prompt);

    var captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(fallback).call(captor.capture());
    var options = (OpenAiImageOptions) captor.getValue().getOptions();
    assertThat(options.getModel()).isEqualTo("local-image-fallback");
    assertThat(options.getQuality()).isEqualTo("high");
    assertThat(options.getWidth()).isEqualTo(512);
    assertThat(options.getResponseFormat()).isEqualTo("b64_json");
    assertThat(prompt.getOptions().getModel()).isEqualTo("feature-model");
  }

  /** 主要なを利用することを確認する。 */
  @Test
  void usesPrimaryWhenPrimarySucceeds() {
    ImageModel primary = Mockito.mock(ImageModel.class);
    ImageModel fallback = Mockito.mock(ImageModel.class);
    ImagePrompt prompt = prompt("feature-model");
    ImageResponse response = response("primary");
    when(primary.call(prompt)).thenReturn(response);

    FallbackImageModel model =
        new FallbackImageModel("image-generation", primary, fallback, "feature-model");

    assertThat(model.call(prompt)).isSameAs(response);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(fallback);
  }

  /** 主要なモデルを取り除くことを確認する。 */
  @Test
  void fallsBackAndRemovesPrimaryModel() {
    ImageModel primary = Mockito.mock(ImageModel.class);
    ImageModel fallback = Mockito.mock(ImageModel.class);
    ImagePrompt prompt = prompt("feature-model");
    ImageResponse response = response("fallback");
    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(ImagePrompt.class))).thenReturn(response);

    FallbackImageModel model =
        new FallbackImageModel("image-generation", primary, fallback, "feature-model");

    assertThat(model.call(prompt)).isSameAs(response);
    ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(fallback).call(captor.capture());
    assertThat(captor.getValue().getOptions().getModel()).isNull();
    assertThat(captor.getValue().getOptions().getWidth()).isEqualTo(512);
  }

  /** 検証で使用するプロンプトを、指定された条件に合わせて用意する。 */
  private ImagePrompt prompt(String model) {
    return new ImagePrompt(
        "cat",
        OpenAiImageOptions.builder()
            .model(model)
            .width(512)
            .height(512)
            .responseFormat("b64_json")
            .build());
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private ImageResponse response(String base64) {
    return new ImageResponse(List.of(new ImageGeneration(new Image(null, base64))));
  }
}
