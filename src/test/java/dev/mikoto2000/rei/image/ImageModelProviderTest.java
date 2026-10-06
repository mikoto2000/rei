package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.image.ImageModel;

import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmProperties;

class ImageModelProviderTest {

  @Test
  void noOverrideUsesConfiguredImageModelRatherThanSdkDefault() {
    var defaultModel = Mockito.mock(org.springframework.ai.openai.OpenAiImageModel.class);
    Mockito.when(defaultModel.getOptions()).thenReturn(org.springframework.ai.openai.OpenAiImageOptions.builder()
        .model("local-image-model").build());
    var provider = new ImageModelProvider(defaultModel, new LlmProperties());
    var client = new OpenAiImageGenerationClient(provider);

    var prompt = client.buildPrompt(new ImageGenerationRequest("cat", null, null, new ImageSize(512, 512)));

    assertThat(provider.model(null)).isEqualTo("local-image-model");
    assertThat(prompt.getOptions().getModel()).isEqualTo("local-image-model");
    assertThat(prompt.getOptions().getResponseFormat()).isEqualTo("b64_json");
  }

  @Test
  void returnsDefaultImageModelWhenFeatureServerIsNotConfigured() {
    ImageModel defaultModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = new ImageModelProvider(defaultModel, new LlmProperties());

    assertThat(provider.imageModel()).isSameAs(defaultModel);
    assertThat(provider.model("override-model")).isEqualTo("override-model");
    assertThat(provider.model(null)).isNull();
  }

  @Test
  void createsFallbackImageModelWhenFeatureServerIsConfigured() {
    ImageModel defaultModel = Mockito.mock(ImageModel.class);
    LlmProperties properties = new LlmProperties();
    LlmProperties.Server server = new LlmProperties.Server();
    server.setBaseUrl("http://feature.example.test");
    server.setApiKey("feature-key");
    server.setModel("feature-model");
    properties.getFeatures().put(LlmFeature.IMAGE_GENERATION, server);

    ImageModelProvider provider = new ImageModelProvider(defaultModel, properties);

    assertThat(provider.imageModel()).isInstanceOf(FallbackImageModel.class);
    assertThat(provider.imageModel()).isSameAs(provider.imageModel());
    assertThat(provider.model(null)).isEqualTo("feature-model");
  }
}
