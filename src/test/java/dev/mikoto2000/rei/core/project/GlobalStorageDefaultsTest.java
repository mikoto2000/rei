package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 全体共通保存既定値の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class GlobalStorageDefaultsTest {
  /** プロジェクトごとの作業環境で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void bundledDefaultsDoNotCreateProjectLocalReiDirectory() throws Exception {
    assertThat(new dev.mikoto2000.rei.config.ExternalConfigFileService().configFilePath())
        .isEqualTo(
            dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
                .resolve("application.yaml"));
    assertThat(Files.readString(Path.of("src/main/resources/application.yaml")))
        .doesNotContain("/.rei", "file:.rei", ":.rei");
    assertThat(Files.readString(Path.of("src/main/resources/logback-spring.xml")))
        .doesNotContain("./.rei");
    assertThat(new dev.mikoto2000.rei.image.ImageProperties().getOutputDirectory().toString())
        .doesNotContain(".rei");
    assertThat(new dev.mikoto2000.rei.core.configuration.SqliteVecProperties().getCacheDir())
        .doesNotContain(".rei");
  }
}
