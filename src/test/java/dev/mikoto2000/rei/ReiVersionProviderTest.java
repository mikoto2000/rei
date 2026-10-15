package dev.mikoto2000.rei;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ReiVersionProviderTest {
  @Test
  void readsBackendBuildVersionBeforeManifestVersion() {
    assertThat(
            provider(
                    "build.group=dev.mikoto2000\nbuild.artifact=rei\nbuild.version=2.4.6-SNAPSHOT\n",
                    "1.2.3")
                .getVersion())
        .containsExactly("rei 2.4.6-SNAPSHOT");
  }

  @Test
  void usesManifestWhenBuildMetadataIsMissingOrBlank() {
    assertThat(provider(null, " 1.2.3 ").getVersion()).containsExactly("rei 1.2.3");
    assertThat(
            provider("build.group=dev.mikoto2000\nbuild.artifact=rei\nbuild.version=   \n", "1.2.3")
                .getVersion())
        .containsExactly("rei 1.2.3");
  }

  @Test
  void neverReportsLightweightClientMetadataAsBackendVersion() {
    assertThat(
            provider(
                    "build.group=dev.mikoto2000\nbuild.artifact=rei-cli\nbuild.version=9.8.7\n",
                    null)
                .getVersion())
        .containsExactly("rei development (version unknown)");
  }

  @Test
  void developmentBuildWithoutMetadataHasExplicitFallback() {
    assertThat(provider(null, null).getVersion())
        .containsExactly("rei development (version unknown)");
    assertThat(provider("build.group=dev.mikoto2000\nbuild.artifact=rei\n", " ").getVersion())
        .containsExactly("rei development (version unknown)");
  }

  @Test
  void invalidOrUnreadableMetadataDoesNotBreakVersionCommand() {
    assertThat(provider("build.version=\\uINVALID\n", "1.2.3").getVersion())
        .containsExactly("rei 1.2.3");
    var unreadable =
        new ClassLoader(null) {
          @Override
          public InputStream getResourceAsStream(String name) {
            return new InputStream() {
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

  private ReiVersionProvider provider(String metadata, String manifestVersion) {
    var loader =
        new ClassLoader(null) {
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
