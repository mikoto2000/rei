package dev.mikoto2000.rei.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageOutputPathResolverTest {

  @TempDir
  Path tempDir;

  @Test
  void backgroundPathsUseCapturedProjectAndUniqueExecutionId() {
    var properties=new ImageProperties(); properties.setOutputDirectory(Path.of("images"));
    var resolver=new ImageOutputPathResolver(tempDir,properties,fixedClock());
    var root=tempDir.resolve("project-a");
    var execution=new dev.mikoto2000.rei.core.execution.ActiveExecution("unique-id",java.util.UUID.randomUUID().toString(),"chat:main",root,
        dev.mikoto2000.rei.core.execution.ExecutionType.IMAGE,"picture",Instant.now());
    try(var scope=dev.mikoto2000.rei.core.execution.ExecutionScope.open(execution)) {
      assertThat(resolver.resolve(Path.of("out.png"))).isEqualTo(root.resolve("out.png"));
      assertThat(resolver.resolve(null)).isEqualTo(root.resolve("images/image-20260814-123456-unique-id.png"));
    }
  }

  @Test
  void resolvesExplicitRelativePathFromWorkDirectory() {
    ImageProperties properties = new ImageProperties();
    ImageOutputPathResolver resolver = new ImageOutputPathResolver(tempDir, properties, fixedClock());

    assertThat(resolver.resolve(Path.of("images", "out.png"))).isEqualTo(tempDir.resolve("images/out.png"));
  }

  @Test
  void resolvesDefaultPathFromConfiguredOutputDirectory() {
    ImageProperties properties = new ImageProperties();
    properties.setOutputDirectory(tempDir.resolve("generated"));
    ImageOutputPathResolver resolver = new ImageOutputPathResolver(tempDir, properties, fixedClock());

    assertThat(resolver.resolve(null)).isEqualTo(tempDir.resolve("generated/image-20260814-123456.png"));
  }

  private Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-08-14T12:34:56Z"), ZoneId.of("UTC"));
  }
}
