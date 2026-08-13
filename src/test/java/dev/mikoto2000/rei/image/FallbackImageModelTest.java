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

class FallbackImageModelTest {

  @Test
  void usesPrimaryWhenPrimarySucceeds() {
    ImageModel primary = Mockito.mock(ImageModel.class);
    ImageModel fallback = Mockito.mock(ImageModel.class);
    ImagePrompt prompt = prompt("feature-model");
    ImageResponse response = response("primary");
    when(primary.call(prompt)).thenReturn(response);

    FallbackImageModel model = new FallbackImageModel("image-generation", primary, fallback, "feature-model");

    assertThat(model.call(prompt)).isSameAs(response);
    verifyNoInteractions(fallback);
  }

  @Test
  void fallsBackAndRemovesPrimaryModel() {
    ImageModel primary = Mockito.mock(ImageModel.class);
    ImageModel fallback = Mockito.mock(ImageModel.class);
    ImagePrompt prompt = prompt("feature-model");
    ImageResponse response = response("fallback");
    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(ImagePrompt.class))).thenReturn(response);

    FallbackImageModel model = new FallbackImageModel("image-generation", primary, fallback, "feature-model");

    assertThat(model.call(prompt)).isSameAs(response);
    ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
    verify(fallback).call(captor.capture());
    assertThat(captor.getValue().getOptions().getModel()).isNull();
    assertThat(captor.getValue().getOptions().getWidth()).isEqualTo(512);
  }

  private ImagePrompt prompt(String model) {
    return new ImagePrompt("cat", OpenAiImageOptions.builder()
        .model(model)
        .width(512)
        .height(512)
        .responseFormat("b64_json")
        .build());
  }

  private ImageResponse response(String base64) {
    return new ImageResponse(List.of(new ImageGeneration(new Image(null, base64))));
  }
}
