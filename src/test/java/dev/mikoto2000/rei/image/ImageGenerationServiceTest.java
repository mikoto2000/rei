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

/** 画像生成サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ImageGenerationServiceTest {
  /** 自分が所有する対象成果物受付記録を返すことを確認する。 */
  @Test
  void enabledDeliveryCopiesGeneratedBytesAndReturnsAnOwnedArtifactReceipt() throws Exception {
    var buffer = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        buffer);
    var projects =
        new dev.mikoto2000.rei.core.project.ProjectRegistry(tempDir.resolve("projects.json"));
    var project = projects.resolve(tempDir);
    var source = new org.sqlite.SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + tempDir.resolve("state.db"));
    var store =
        new dev.mikoto2000.rei.artifact.ArtifactStore(
            source,
            projects,
            tempDir.resolve("delivery"),
            Clock.systemUTC(),
            new dev.mikoto2000.rei.artifact.ArtifactProperties());
    var service =
        service(new RecordingClient(Base64.getEncoder().encodeToString(buffer.toByteArray())));
    service.setArtifactStore(store);
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext("image", "session", tempDir, project.id());
    ImageGenerationResult result;
    try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
      result =
          service.generate(
              new ImageGenerationRequest(
                  "fixture", tempDir.resolve("生成結果.png"), null, new ImageSize(1, 1), false));
    }
    assertThat(result.success()).isTrue();
    assertThat(result.artifactId()).isNotNull();
    Files.writeString(result.savedPath(), "changed source");
    assertThat(store.content(project.id(), "session", result.artifactId()))
        .isEqualTo(buffer.toByteArray());
    assertThat(store.get(project.id(), "session", result.artifactId()).runId()).isEqualTo("image");
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 生成済み画像を保存することを確認する。 */
  @Test
  void savesGeneratedImageToExplicitOutputPath() throws Exception {
    byte[] png = new byte[] {1, 2, 3};
    RecordingClient client = new RecordingClient(Base64.getEncoder().encodeToString(png));
    ImageGenerationService service = service(client);
    Path output = tempDir.resolve("nested/out.png");

    ImageGenerationResult result =
        service.generate(
            new ImageGenerationRequest("cat", output, "model-a", new ImageSize(512, 512)));

    assertThat(result.success()).isTrue();
    assertThat(result.savedPath()).isEqualTo(output);
    assertThat(Files.readAllBytes(output)).isEqualTo(png);
    assertThat(client.request.prompt()).isEqualTo("cat");
    assertThat(client.request.model()).isEqualTo("model-a");
    assertThat(client.request.size()).isEqualTo(new ImageSize(512, 512));
  }

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void enhancesPromptBeforeCallingClient() {
    byte[] png = new byte[] {4, 5, 6};
    RecordingClient client = new RecordingClient(Base64.getEncoder().encodeToString(png));
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir.resolve("images"));
    ImageOutputPathResolver resolver =
        new ImageOutputPathResolver(
            tempDir,
            properties,
            Clock.fixed(Instant.parse("2026-08-14T12:34:56Z"), ZoneOffset.UTC));
    ImageGenerationService service =
        new ImageGenerationService(
            client, resolver, null, userRequest -> "enhanced " + userRequest, properties);

    ImageGenerationResult result =
        service.generate(new ImageGenerationRequest("cat", null, null, new ImageSize(1, 1)));

    assertThat(result.success()).isTrue();
    assertThat(client.request.prompt()).isEqualTo("enhanced cat");
    assertThat(client.request.enhancePrompt()).isFalse();
    assertThat(result.prompt()).isEqualTo("enhanced cat");
  }

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void skipsPromptEnhancementWhenRequestDisablesIt() {
    RecordingClient client =
        new RecordingClient(Base64.getEncoder().encodeToString(new byte[] {7}));
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir.resolve("images"));
    ImageOutputPathResolver resolver =
        new ImageOutputPathResolver(
            tempDir,
            properties,
            Clock.fixed(Instant.parse("2026-08-14T12:34:56Z"), ZoneOffset.UTC));
    ImageGenerationService service =
        new ImageGenerationService(
            client, resolver, null, userRequest -> "enhanced " + userRequest, properties);

    ImageGenerationResult result =
        service.generate(new ImageGenerationRequest("cat", null, null, new ImageSize(1, 1), false));

    assertThat(result.success()).isTrue();
    assertThat(client.request.prompt()).isEqualTo("cat");
  }

  /** 生成済み画像を保存することを確認する。 */
  @Test
  void savesGeneratedImageToDefaultOutputDirectory() {
    RecordingClient client =
        new RecordingClient(Base64.getEncoder().encodeToString(new byte[] {9}));
    ImageGenerationService service = service(client);

    ImageGenerationResult result =
        service.generate(new ImageGenerationRequest("dog", null, null, new ImageSize(1024, 1024)));

    assertThat(result.success()).isTrue();
    assertThat(result.savedPath()).isEqualTo(tempDir.resolve("images/image-20260814-123456.png"));
    assertThat(result.savedPath()).exists();
  }

  /** 失敗情報を返すことを確認する。 */
  @Test
  void returnsFailureWhenPromptIsBlankWithoutCallingClient() {
    RecordingClient client = new RecordingClient("unused");
    ImageGenerationService service = service(client);

    ImageGenerationResult result =
        service.generate(
            new ImageGenerationRequest(" ", tempDir.resolve("out.png"), null, new ImageSize(1, 1)));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("プロンプト");
    assertThat(client.request).isNull();
  }

  /** 失敗情報を返すことを確認する。 */
  @Test
  void returnsFailureWhenClientFails() {
    ImageGenerationService service =
        service(
            request -> {
              throw new IllegalStateException("api failed");
            });

    ImageGenerationResult result =
        service.generate(
            new ImageGenerationRequest(
                "cat", tempDir.resolve("out.png"), null, new ImageSize(1, 1)));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("api failed");
  }

  /** 失敗情報を返すことを確認する。 */
  @Test
  void returnsFailureWhenBase64IsInvalid() {
    ImageGenerationService service = service(new RecordingClient("not base64"));

    ImageGenerationResult result =
        service.generate(
            new ImageGenerationRequest(
                "cat", tempDir.resolve("out.png"), null, new ImageSize(1, 1)));

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("デコード");
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  private ImageGenerationService service(ImageGenerationClient client) {
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir.resolve("images"));
    ImageOutputPathResolver resolver =
        new ImageOutputPathResolver(
            tempDir,
            properties,
            Clock.fixed(Instant.parse("2026-08-14T12:34:56Z"), ZoneOffset.UTC));
    return new ImageGenerationService(client, resolver);
  }

  /** 検証条件を再現するためのRecordingクライアント。 */
  private static class RecordingClient implements ImageGenerationClient {
    /** 検証に使う応答。 */
    private final String response;

    /** 検証に使う要求。 */
    private ImageGenerationRequest request;

    /** 検証条件に必要な値を受け取り、RecordingClientを用意する。 */
    RecordingClient(String response) {
      this.response = response;
    }

    /** {@code generate} の条件を再現するための補助情報を返す。 */
    @Override
    public String generate(ImageGenerationRequest request) {
      this.request = request;
      return response;
    }
  }
}
