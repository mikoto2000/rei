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

/** 開始AI画像生成クライアントの結果と状態の契約を検証するテスト。 */
class OpenAiImageGenerationClientTest {

  /** 画像の生成で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void requestOverridesDoNotReplaceConfiguredSdkTimeout() {
    var timeout = new java.util.concurrent.atomic.AtomicInteger();
    var model =
        org.springframework.ai.openai.OpenAiImageModel.builder()
            .options(
                org.springframework.ai.openai.OpenAiImageOptions.builder()
                    .baseUrl("https://image.invalid/v1")
                    .apiKey("test")
                    .model("configured-image")
                    .timeout(java.time.Duration.ofSeconds(117))
                    .maxRetries(0)
                    .build())
            .httpClientBuilderCustomizer(
                builder ->
                    builder.interceptor(
                        chain -> {
                          timeout.set(chain.readTimeoutMillis());
                          return new okhttp3.Response.Builder()
                              .request(chain.request())
                              .protocol(okhttp3.Protocol.HTTP_1_1)
                              .code(200)
                              .message("OK")
                              .body(
                                  okhttp3.ResponseBody.create(
                                      "{\"created\":0,\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]}",
                                      okhttp3.MediaType.get("application/json")))
                              .build();
                        }))
            .build();
    var provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(model);
    when(provider.model(null)).thenReturn("configured-image");
    var client = new OpenAiImageGenerationClient(provider);

    assertThat(
            client.generate(new ImageGenerationRequest("cat", null, null, new ImageSize(512, 512))))
        .isEqualTo("aW1hZ2U=");
    assertThat(timeout).hasValue(117000);
  }

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void buildsImagePromptWithOnlyRequestedOverrides() {
    ImageModel imageModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(imageModel);
    when(provider.model("override-model")).thenReturn("override-model");
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider);

    ImagePrompt prompt =
        client.buildPrompt(
            new ImageGenerationRequest("a cat", null, "override-model", new ImageSize(640, 480)));

    assertThat(prompt.getInstructions().getFirst().getText()).isEqualTo("a cat");
    assertThat(prompt.getOptions().getModel()).isEqualTo("override-model");
    assertThat(prompt.getOptions())
        .isNotInstanceOf(org.springframework.ai.openai.OpenAiImageOptions.class);
    assertThat(prompt.getOptions().getWidth()).isEqualTo(640);
    assertThat(prompt.getOptions().getHeight()).isEqualTo(480);
    assertThat(prompt.getOptions().getResponseFormat()).isEqualTo("b64_json");
  }

  /** 画像の生成で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void omitsResponseFormatForGptImageModelInAutoMode() {
    ImageModel imageModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(imageModel);
    when(provider.model("gpt-image-1")).thenReturn("gpt-image-1");
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider);

    ImagePrompt prompt =
        client.buildPrompt(
            new ImageGenerationRequest("a cat", null, "gpt-image-1", new ImageSize(640, 480)));

    assertThat(prompt.getOptions().getModel()).isEqualTo("gpt-image-1");
    assertThat(prompt.getOptions().getResponseFormat()).isNull();
  }

  /** 画像の生成で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void omitsResponseFormatWhenConfiguredAsNone() {
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.model("local-model")).thenReturn("local-model");
    ImageProperties properties = new ImageProperties();
    properties.setResponseFormat("none");
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider, properties);

    ImagePrompt prompt =
        client.buildPrompt(
            new ImageGenerationRequest("a cat", null, "local-model", new ImageSize(640, 480)));

    assertThat(prompt.getOptions().getResponseFormat()).isNull();
  }

  /** 応答形式を送信することを確認する。 */
  @Test
  void sendsResponseFormatWhenConfiguredAsBase64Json() {
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.model(null)).thenReturn(null);
    ImageProperties properties = new ImageProperties();
    properties.setResponseFormat("b64_json");
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider, properties);

    ImagePrompt prompt =
        client.buildPrompt(
            new ImageGenerationRequest("a cat", null, null, new ImageSize(640, 480)));

    assertThat(prompt.getOptions().getResponseFormat()).isEqualTo("b64_json");
  }

  /** 基準64画像データを返すことを確認する。 */
  @Test
  void returnsBase64ImageDataFromImageModel() {
    ImageModel imageModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(imageModel);
    when(provider.model(null)).thenReturn("image-model");
    when(imageModel.call(Mockito.any(ImagePrompt.class))).thenReturn(response("abc"));
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider);

    assertThat(client.generate(new ImageGenerationRequest("cat", null, null, new ImageSize(1, 1))))
        .isEqualTo("abc");
  }

  /** 未指定画像データを受け付けないことを確認する。 */
  @Test
  void rejectsMissingImageData() {
    ImageModel imageModel = Mockito.mock(ImageModel.class);
    ImageModelProvider provider = Mockito.mock(ImageModelProvider.class);
    when(provider.imageModel()).thenReturn(imageModel);
    when(imageModel.call(Mockito.any(ImagePrompt.class))).thenReturn(response(""));
    OpenAiImageGenerationClient client = new OpenAiImageGenerationClient(provider);

    assertThatThrownBy(
            () ->
                client.generate(new ImageGenerationRequest("cat", null, null, new ImageSize(1, 1))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("画像データ");
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private ImageResponse response(String base64) {
    return new ImageResponse(List.of(new ImageGeneration(new Image(null, base64))));
  }
}
