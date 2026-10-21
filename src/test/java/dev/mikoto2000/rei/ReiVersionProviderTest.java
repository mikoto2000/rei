package dev.mikoto2000.rei;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** れい形式のバージョン提供元の結果と状態の契約を検証するテスト。 */
class ReiVersionProviderTest {
  /** バックエンド構築形式のバージョンを読み取ることを確認する。 */
  @Test
  void readsBackendBuildVersionBeforeManifestVersion() {
    assertThat(
            provider(
                    "build.group=dev.mikoto2000\nbuild.artifact=rei\nbuild.version=2.4.6-SNAPSHOT\n",
                    "1.2.3")
                .getVersion())
        .containsExactly("rei 2.4.6-SNAPSHOT");
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void usesManifestWhenBuildMetadataIsMissingOrBlank() {
    assertThat(provider(null, " 1.2.3 ").getVersion()).containsExactly("rei 1.2.3");
    assertThat(
            provider("build.group=dev.mikoto2000\nbuild.artifact=rei\nbuild.version=   \n", "1.2.3")
                .getVersion())
        .containsExactly("rei 1.2.3");
  }

  /** れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void neverReportsLightweightClientMetadataAsBackendVersion() {
    assertThat(
            provider(
                    "build.group=dev.mikoto2000\nbuild.artifact=rei-cli\nbuild.version=9.8.7\n",
                    null)
                .getVersion())
        .containsExactly("rei development (version unknown)");
  }

  /** れいの起動で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void developmentBuildWithoutMetadataHasExplicitFallback() {
    assertThat(provider(null, null).getVersion())
        .containsExactly("rei development (version unknown)");
    assertThat(provider("build.group=dev.mikoto2000\nbuild.artifact=rei\n", " ").getVersion())
        .containsExactly("rei development (version unknown)");
  }

  /** れいの起動で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidOrUnreadableMetadataDoesNotBreakVersionCommand() {
    assertThat(provider("build.version=\\uINVALID\n", "1.2.3").getVersion())
        .containsExactly("rei 1.2.3");
    var unreadable =
        new ClassLoader(null) {
          /** 検証用に、れいの起動で参照する補助資料形式逐次データを返す。 */
          @Override
          public InputStream getResourceAsStream(String name) {
            return new InputStream() {
              /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
              @Override
              public int read() throws IOException {
                throw new IOException("unreadable metadata");
              }
            };
          }
        };
    assertThat(new ReiVersionProvider(unreadable, null).getVersion())
        .containsExactly("rei development (version unknown)");
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void mavenBuildGeneratesMetadataForExplodedClasses() throws IOException {
    var metadata = new Properties();
    try (var input =
        ReiVersionProvider.class.getResourceAsStream("/META-INF/build-info.properties")) {
      assertThat(input).isNotNull();
      metadata.load(input);
    }
    assertThat(metadata.getProperty("build.artifact")).isEqualTo("rei");
    assertThat(metadata.getProperty("build.version")).isNotBlank();
    assertThat(new ReiVersionProvider().getVersion())
        .containsExactly("rei " + metadata.getProperty("build.version"));
  }

  /** 検証で使用する提供元を、指定された条件に合わせて用意する。 */
  private ReiVersionProvider provider(String metadata, String manifestVersion) {
    var loader =
        new ClassLoader(null) {
          /** 検証用に、れいの起動で参照する補助資料形式逐次データを返す。 */
          @Override
          public InputStream getResourceAsStream(String name) {
            assertThat(name).isEqualTo("META-INF/build-info.properties");
            return metadata == null
                ? null
                : new ByteArrayInputStream(metadata.getBytes(StandardCharsets.ISO_8859_1));
          }
        };
    return new ReiVersionProvider(loader, manifestVersion);
  }
}
