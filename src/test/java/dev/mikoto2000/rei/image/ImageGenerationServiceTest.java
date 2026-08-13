package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageGenerationServiceTest {

  @TempDir
  Path tempDir;

  @Test
  void savesGeneratedImageToExplicitOutputPath() throws Exception {
    byte[] png = new byte[] {1, 2, 3};
    RecordingClient client = new RecordingClient(Base64.getEncoder().encodeToString(png));
    ImageGenerationService service = service(client);
    Path output = tempDir.resolve("nested/out.png");

    ImageGenerationResult result = service.generate(new ImageGenerationRequest("cat", output, "model-a", new ImageSize(512, 512)));

    assertThat(result.success()).isTrue();
    assertThat(result.savedPath()).isEqualTo(output);
    assertThat(Files.readAllBytes(output)).isEqualTo(png);
    assertThat(client.request.prompt()).isEqualTo("cat");
    assertThat(client.request.model()).isEqualTo("model-a");
    assertThat(client.request.size()).isEqualTo(new ImageSize(512, 512));
  }

  @Test
  void savesGeneratedImageToDefaultOutputDirectory() {
    RecordingClient client = new RecordingClient(Base64.getEncoder().encodeToString(new byte[] {9}));
    ImageGenerationService service = service(client);

    ImageGenerationResult result = service.generate(new ImageGenerationRequest("dog", null, null, new ImageSize(1024, 1024)));

    assertThat(result.success()).isTrue();
    assertThat(result.savedPath()).isEqualTo(tempDir.resolve("images/image-20260814-123456.png"));
    assertThat(result.savedPath()).exists();
  }

  @Test
  void returnsFailureWhenPromptIsBlankWithoutCallingClient() {
    RecordingClient client = new RecordingClient("unused");
    ImageGenerationService service = service(client);

    ImageGenerationResult result = service.generate(new ImageGenerationRequest(" ", tempDir.resolve("out.png"), null, new ImageSize(1, 1)));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("プロンプト");
    assertThat(client.request).isNull();
  }

  @Test
  void returnsFailureWhenClientFails() {
    ImageGenerationService service = service(request -> {
      throw new IllegalStateException("api failed");
    });

    ImageGenerationResult result = service.generate(new ImageGenerationRequest("cat", tempDir.resolve("out.png"), null, new ImageSize(1, 1)));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("api failed");
  }

  @Test
  void returnsFailureWhenBase64IsInvalid() {
    ImageGenerationService service = service(new RecordingClient("not base64"));

    ImageGenerationResult result = service.generate(new ImageGenerationRequest("cat", tempDir.resolve("out.png"), null, new ImageSize(1, 1)));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("デコード");
  }

  private ImageGenerationService service(ImageGenerationClient client) {
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir.resolve("images"));
    ImageOutputPathResolver resolver = new ImageOutputPathResolver(tempDir, properties,
        Clock.fixed(Instant.parse("2026-08-14T12:34:56Z"), ZoneOffset.UTC));
    return new ImageGenerationService(client, resolver);
  }

  private static class RecordingClient implements ImageGenerationClient {
    private final String response;
    private ImageGenerationRequest request;

    RecordingClient(String response) {
      this.response = response;
    }

    @Override
    public String generate(ImageGenerationRequest request) {
      this.request = request;
      return response;
    }
  }
}
