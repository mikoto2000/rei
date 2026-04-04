package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import tools.jackson.databind.json.JsonMapper;

class SqliteVecInstallerTest {

  @TempDir
  Path tempDir;

  @Test
  void reusesCachedLibraryWithoutDownloading() throws Exception {
    SqliteVecProperties properties = properties();
    properties.setCacheDir(tempDir.toString());
    Path cached = tempDir.resolve("0.1.9").resolve("linux-x86_64").resolve("vec0.so");
    Files.createDirectories(cached.getParent());
    Files.writeString(cached, "cached");

    RecordingDownloadClient downloadClient = new RecordingDownloadClient(Map.of());
    SqliteVecInstaller installer = new SqliteVecInstaller(
        properties,
        () -> SqliteVecPlatform.LINUX_X86_64,
        new SqliteVecAssetResolver(),
        new JsonMapper(),
        downloadClient);

    assertEquals(cached, installer.resolveExtensionPath());
    assertEquals(0, downloadClient.requestCount());
  }

  @Test
  void downloadsManifestAndArchiveThenExtractsLibrary() throws Exception {
    SqliteVecProperties properties = properties();
    properties.setCacheDir(tempDir.toString());
    String assetName = "sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz";
    byte[] archiveBytes = createTarGz("vec0.so", "sqlite-vec");
    String checksum = sha256(archiveBytes);
    String manifest = """
        {
          "artifacts": [
            {
              "kind": "github-release-loadable",
              "name": "%s",
              "checksum_sha256": "%s"
            }
          ]
        }
        """.formatted(assetName, checksum);

    Map<URI, byte[]> responses = new HashMap<>();
    responses.put(URI.create("https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-dist-manifest.json"),
        manifest.getBytes(StandardCharsets.UTF_8));
    responses.put(URI.create("https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/" + assetName), archiveBytes);

    RecordingDownloadClient downloadClient = new RecordingDownloadClient(responses);
    SqliteVecInstaller installer = new SqliteVecInstaller(
        properties,
        () -> SqliteVecPlatform.LINUX_X86_64,
        new SqliteVecAssetResolver(),
        new JsonMapper(),
        downloadClient);

    Path installed = installer.resolveExtensionPath();

    assertEquals(tempDir.resolve("0.1.9").resolve("linux-x86_64").resolve("vec0.so"), installed);
    assertEquals("sqlite-vec", Files.readString(installed));
  }

  @Test
  void failsWhenChecksumDoesNotMatch() {
    SqliteVecProperties properties = properties();
    properties.setCacheDir(tempDir.toString());
    String assetName = "sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz";
    byte[] archiveBytes = "bad".getBytes(StandardCharsets.UTF_8);
    String manifest = """
        {
          "artifacts": [
            {
              "kind": "github-release-loadable",
              "name": "%s",
              "checksum_sha256": "%s"
            }
          ]
        }
        """.formatted(assetName, "deadbeef");

    Map<URI, byte[]> responses = new HashMap<>();
    responses.put(URI.create("https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-dist-manifest.json"),
        manifest.getBytes(StandardCharsets.UTF_8));
    responses.put(URI.create("https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/" + assetName), archiveBytes);

    SqliteVecInstaller installer = new SqliteVecInstaller(
        properties,
        () -> SqliteVecPlatform.LINUX_X86_64,
        new SqliteVecAssetResolver(),
        new JsonMapper(),
        new RecordingDownloadClient(responses));

    assertThrows(IllegalStateException.class, installer::resolveExtensionPath);
  }

  private SqliteVecProperties properties() {
    SqliteVecProperties properties = new SqliteVecProperties();
    properties.setVersion("0.1.9");
    properties.setAutoDownload(true);
    properties.setReleaseBaseUrl("https://github.com/asg017/sqlite-vec/releases/download");
    return properties;
  }

  private byte[] createTarGz(String entryName, String contents) throws IOException {
    ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
    try (var gzip = new java.util.zip.GZIPOutputStream(tarBytes);
        var tar = new TarArchiveOutputStream(gzip)) {
      byte[] contentBytes = contents.getBytes(StandardCharsets.UTF_8);
      TarArchiveEntry entry = new TarArchiveEntry(entryName);
      entry.setSize(contentBytes.length);
      tar.putArchiveEntry(entry);
      tar.write(contentBytes);
      tar.closeArchiveEntry();
      tar.finish();
    }
    return tarBytes.toByteArray();
  }

  private String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return java.util.HexFormat.of().formatHex(digest.digest(bytes));
  }

  private static final class RecordingDownloadClient implements SqliteVecInstaller.DownloadClient {

    private final Map<URI, byte[]> responses;
    private int requestCount;

    private RecordingDownloadClient(Map<URI, byte[]> responses) {
      this.responses = responses;
    }

    @Override
    public byte[] download(URI uri) {
      requestCount++;
      byte[] response = responses.get(uri);
      if (response == null) {
        throw new IllegalStateException("Unexpected download: " + uri);
      }
      return response;
    }

    private int requestCount() {
      return requestCount;
    }
  }
}
