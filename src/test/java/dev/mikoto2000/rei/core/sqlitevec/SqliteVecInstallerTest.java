package dev.mikoto2000.rei.core.sqlitevec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
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
import tools.jackson.databind.json.JsonMapper;

/** SQLiteベクトルInstallerの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SqliteVecInstallerTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 保存済み保管資料を再利用することを確認する。 */
  @Test
  void reusesCachedLibraryWithoutDownloading() throws Exception {
    SqliteVecProperties properties = properties();
    properties.setCacheDir(tempDir.toString());
    Path cached = tempDir.resolve("0.1.9").resolve("linux-x86_64").resolve("vec0.so");
    Files.createDirectories(cached.getParent());
    Files.writeString(cached, "cached");

    RecordingDownloadClient downloadClient = new RecordingDownloadClient(Map.of());
    SqliteVecInstaller installer =
        new SqliteVecInstaller(
            properties,
            () -> SqliteVecPlatform.LINUX_X86_64,
            new SqliteVecAssetResolver(),
            new JsonMapper(),
            downloadClient);

    assertEquals(cached, installer.resolveExtensionPath());
    // Fresh test fixtures create fresh installers but must reuse the immutable binary.
    SqliteVecInstaller nextFixture =
        new SqliteVecInstaller(
            properties,
            () -> SqliteVecPlatform.LINUX_X86_64,
            new SqliteVecAssetResolver(),
            new JsonMapper(),
            downloadClient);
    assertEquals(cached, nextFixture.resolveExtensionPath());
    assertEquals(0, downloadClient.requestCount());
  }

  /** 文書検索用の保存基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void downloadsManifestAndArchiveThenExtractsLibrary() throws Exception {
    SqliteVecProperties properties = properties();
    properties.setCacheDir(tempDir.toString());
    String assetName = "sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz";
    byte[] archiveBytes = createTarGz("vec0.so", "sqlite-vec");
    String checksum = sha256(archiveBytes);
    String manifest =
        """
        {
          "artifacts": [
            {
              "kind": "github-release-loadable",
              "name": "%s",
              "checksum_sha256": "%s"
            }
          ]
        }
        """
            .formatted(assetName, checksum);

    Map<URI, byte[]> responses = new HashMap<>();
    responses.put(
        URI.create(
            "https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-dist-manifest.json"),
        manifest.getBytes(StandardCharsets.UTF_8));
    responses.put(
        URI.create("https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/" + assetName),
        archiveBytes);

    RecordingDownloadClient downloadClient = new RecordingDownloadClient(responses);
    SqliteVecInstaller installer =
        new SqliteVecInstaller(
            properties,
            () -> SqliteVecPlatform.LINUX_X86_64,
            new SqliteVecAssetResolver(),
            new JsonMapper(),
            downloadClient);

    Path installed = installer.resolveExtensionPath();

    assertEquals(tempDir.resolve("0.1.9").resolve("linux-x86_64").resolve("vec0.so"), installed);
    assertEquals("sqlite-vec", Files.readString(installed));
  }

  /** 文書検索用の保存基盤で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failsWhenChecksumDoesNotMatch() {
    SqliteVecProperties properties = properties();
    properties.setCacheDir(tempDir.toString());
    String assetName = "sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz";
    byte[] archiveBytes = "bad".getBytes(StandardCharsets.UTF_8);
    String manifest =
        """
        {
          "artifacts": [
            {
              "kind": "github-release-loadable",
              "name": "%s",
              "checksum_sha256": "%s"
            }
          ]
        }
        """
            .formatted(assetName, "deadbeef");

    Map<URI, byte[]> responses = new HashMap<>();
    responses.put(
        URI.create(
            "https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/sqlite-dist-manifest.json"),
        manifest.getBytes(StandardCharsets.UTF_8));
    responses.put(
        URI.create("https://github.com/asg017/sqlite-vec/releases/download/v0.1.9/" + assetName),
        archiveBytes);

    SqliteVecInstaller installer =
        new SqliteVecInstaller(
            properties,
            () -> SqliteVecPlatform.LINUX_X86_64,
            new SqliteVecAssetResolver(),
            new JsonMapper(),
            new RecordingDownloadClient(responses));

    assertThrows(IllegalStateException.class, installer::resolveExtensionPath);
  }

  /** {@code properties} の条件を再現するための補助情報を返す。 */
  private SqliteVecProperties properties() {
    SqliteVecProperties properties = new SqliteVecProperties();
    properties.setVersion("0.1.9");
    properties.setAutoDownload(true);
    properties.setReleaseBaseUrl("https://github.com/asg017/sqlite-vec/releases/download");
    return properties;
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
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

  /** {@code sha256} の条件を再現するための補助情報を返す。 */
  private String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return java.util.HexFormat.of().formatHex(digest.digest(bytes));
  }

  /** 検証条件を再現するためのRecording取得クライアント。 */
  private static final class RecordingDownloadClient implements SqliteVecInstaller.DownloadClient {

    /** 検証に使う応答一覧。 */
    private final Map<URI, byte[]> responses;

    /** 検証に使う要求件数。 */
    private int requestCount;

    /** 検証条件に必要な値を受け取り、RecordingDownloadClientを用意する。 */
    private RecordingDownloadClient(Map<URI, byte[]> responses) {
      this.responses = responses;
    }

    /** {@code download} の条件を再現するための補助情報を返す。 */
    @Override
    public byte[] download(URI uri) {
      requestCount++;
      byte[] response = responses.get(uri);
      if (response == null) {
        throw new IllegalStateException("Unexpected download: " + uri);
      }
      return response;
    }

    /** {@code requestCount} の条件を再現するための補助情報を返す。 */
    private int requestCount() {
      return requestCount;
    }
  }
}
