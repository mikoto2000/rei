package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 画像出力パス対象の解決の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ImageOutputPathResolverTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** バックグラウンドパス一覧について、取得済みプロジェクトを利用することを確認する。 */
  @Test
  void backgroundPathsUseCapturedProjectAndUniqueExecutionId() {
    var properties = new ImageProperties();
    properties.setOutputDirectory(Path.of("images"));
    var resolver = new ImageOutputPathResolver(tempDir, properties, fixedClock());
    var root = tempDir.resolve("project-a");
    var execution =
        new dev.mikoto2000.rei.core.execution.ActiveExecution(
            "unique-id",
            java.util.UUID.randomUUID().toString(),
            "chat:main",
            root,
            dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE,
            "picture",
            Instant.now());
    try (var scope = dev.mikoto2000.rei.core.execution.ExecutionScope.open(execution)) {
      assertThat(resolver.resolve(Path.of("out.png"))).isEqualTo(root.resolve("out.png"));
      assertThat(resolver.resolve(null))
          .isEqualTo(root.resolve("images/image-20260814-123456-unique-id.png"));
    }
  }

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resolvesExplicitRelativePathFromWorkDirectory() {
    ImageProperties properties = new ImageProperties();
    ImageOutputPathResolver resolver =
        new ImageOutputPathResolver(tempDir, properties, fixedClock());

    assertThat(resolver.resolve(Path.of("images", "out.png")))
        .isEqualTo(tempDir.resolve("images/out.png"));
  }

  /** 画像の生成で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resolvesDefaultPathFromConfiguredOutputDirectory() {
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir.resolve("generated"));
    ImageOutputPathResolver resolver =
        new ImageOutputPathResolver(tempDir, properties, fixedClock());

    assertThat(resolver.resolve(null))
        .isEqualTo(tempDir.resolve("generated/image-20260814-123456.png"));
  }

  /** {@code fixedClock} の条件を再現するための補助情報を返す。 */
  private Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-08-14T12:34:56Z"), ZoneId.of("UTC"));
  }
}
