package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

class OpenAiImageGenerationClientTest {

  @Test
  void buildsImagePromptWithOpenAiOptions() {
    ImageModel imageModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(imageModel);
    when(provider.model("override-model")).thenReturn("override-model");
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider);

    ImagePrompt prompt = client.buildPrompt(new ImageGenerationRequest("a cat", null, "override-model", new ImageSize(640, 480)));

    assertThat(prompt.getInstructions().getFirst().getText()).isEqualTo("a cat");
    assertThat(prompt.getOptions().getModel()).isEqualTo("override-model");
    assertThat(prompt.getOptions().getWidth()).isEqualTo(640);
    assertThat(prompt.getOptions().getHeight()).isEqualTo(480);
    assertThat(prompt.getOptions().getResponseFormat()).isEqualTo("b64_json");
  }

  @Test
  void returnsBase64ImageDataFromImageModel() {
    ImageModel imageModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(imageModel);
    when(provider.model(null)).thenReturn("image-model");
    when(imageModel.call(Mockito.any(ImagePrompt.class))).thenReturn(response("abc"));
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider);

    assertThat(client.generate(new ImageGenerationRequest("cat", null, null, new ImageSize(1, 1)))).isEqualTo("abc");
  }

  @Test
  void rejectsMissingImageData() {
    ImageModel imageModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(imageModel);
    when(imageModel.call(Mockito.any(ImagePrompt.class))).thenReturn(response(""));
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider);

    assertThatThrownBy(() -> client.generate(new ImageGenerationRequest("cat", null, null, new ImageSize(1, 1))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("画像データ");
  }

  private ImageResponse response(String base64) {
    return new ImageResponse(List.of(new ImageGeneration(new Image(null, base64))));
  }
}
