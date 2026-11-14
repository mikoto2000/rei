package dev.mikoto2000.rei.core.sqlitevec;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 文書検索用の保存基盤に必要なSQLiteベクトルInstallerを扱う。 */
@Component
public class SqliteVecInstaller {

  /** この機能の動作設定。 */
  private final SqliteVecProperties properties;

  /** 実行環境取得元。 */
  private final PlatformSupplier platformSupplier;

  /** 素材対象の解決。 */
  private final SqliteVecAssetResolver assetResolver;

  /** GitHub release v0.1.9 の asset digest。取得時に配信元から読み直さない。 */
  private static final Map<String, String> CHECKSUMS =
      Map.of(
          "sqlite-vec-0.1.9-loadable-linux-aarch64.tar.gz",
          "ea03d39541e478fab5974253c461e1cb5d77742f69e40cf96e3fad5bc309a37c",
          "sqlite-vec-0.1.9-loadable-linux-x86_64.tar.gz",
          "b959baa1d8dc88861b1edb337b8587178cdcb12d60b4998f9d10b6a82052d5d7",
          "sqlite-vec-0.1.9-loadable-macos-aarch64.tar.gz",
          "8282126333399ddfe98bbbcc7a1936e7252625aac49df056a98be602e46bfd29",
          "sqlite-vec-0.1.9-loadable-macos-x86_64.tar.gz",
          "53ad76e400786515e2edcaed2f01271dda846316390b761fadbd2dcf56aa4713",
          "sqlite-vec-0.1.9-loadable-windows-x86_64.tar.gz",
          "51581189d52066b4dfc6631f6d7a3eab7dedc2260656ab09ca97ab3fb8165983");

  /** ダウンロードを許可するアーカイブの SHA-256。 */
  private final Function<String, String> checksums;

  /** 取得クライアント。 */
  private final DownloadClient downloadClient;

  /** 文書検索用の保存基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public SqliteVecInstaller(
      SqliteVecProperties properties,
      PlatformDetector platformDetector,
      SqliteVecAssetResolver assetResolver) {
    this(
        properties,
        platformDetector::detect,
        assetResolver,
        CHECKSUMS::get,
        new HttpDownloadClient());
  }

  /** 指定された値や利用先を使って、SqliteVecInstallerを初期化する。 */
  SqliteVecInstaller(
      SqliteVecProperties properties,
      PlatformSupplier platformSupplier,
      SqliteVecAssetResolver assetResolver,
      Function<String, String> checksums,
      DownloadClient downloadClient) {
    this.properties = properties;
    this.platformSupplier = platformSupplier;
    this.assetResolver = assetResolver;
    this.checksums = checksums;
    this.downloadClient = downloadClient;
  }

  /** 指定や設定に応じた対象を利用するため、拡張子パスを確定する。 */
  public synchronized Path resolveExtensionPath() {
    if (properties.getExtensionPath() != null && !properties.getExtensionPath().isBlank()) {
      Path configuredPath = Path.of(properties.getExtensionPath()).toAbsolutePath().normalize();
      if (!Files.exists(configuredPath)) {
        throw new IllegalStateException("sqlite-vec extension が見つかりません: " + configuredPath);
      }
      return configuredPath;
    }

    SqliteVecPlatform platform = platformSupplier.get();
    SqliteVecArtifact artifact = assetResolver.resolve(properties.getVersion(), platform);
    Path installDir =
        Path.of(properties.getCacheDir()).resolve(properties.getVersion()).resolve(platform.id());
    Path libraryPath = installDir.resolve(artifact.libraryFileName());
    if (Files.exists(libraryPath)) {
      return libraryPath;
    }
    if (!properties.isAutoDownload()) {
      throw new IllegalStateException("sqlite-vec extension が未配置で、自動ダウンロードも無効です: " + libraryPath);
    }

    String checksum = checksums.apply(artifact.assetName());
    if (checksum == null) {
      throw new IllegalStateException(
          "sqlite-vec の自動取得に対応していない version/platform です。extension-path を設定してください");
    }

    try {
      byte[] archiveBytes = downloadClient.download(assetUri(artifact.assetName()));
      verifyChecksum(archiveBytes, checksum);
      Files.createDirectories(installDir);
      extractLibrary(archiveBytes, installDir, artifact.libraryFileName());
      return libraryPath;
    } catch (IOException e) {
      throw new IllegalStateException("sqlite-vec extension の準備に失敗しました", e);
    }
  }

  /** 文書検索用の保存基盤で用いる素材URIを返す。 */
  private URI assetUri(String assetName) {
    return URI.create(
        "%s/v%s/%s".formatted(properties.getReleaseBaseUrl(), properties.getVersion(), assetName));
  }

  /** 要求された条件を満たすか判断するため、検査値を確認する。 */
  private void verifyChecksum(byte[] bytes, String expectedChecksum) {
    String actualChecksum;
    try {
      actualChecksum =
          java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 が利用できません", e);
    }
    if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
      throw new IllegalStateException("sqlite-vec archive の checksum が一致しません");
    }
  }

  /** 必要なライブラリだけを一時ファイルへ展開し、完全に書き込んだ後で公開する。 */
  private void extractLibrary(byte[] archiveBytes, Path installDir, String libraryName)
      throws IOException {
    Path libraryPath = installDir.resolve(libraryName);
    try (InputStream inputStream = new java.io.ByteArrayInputStream(archiveBytes);
        var gzipInputStream = new java.util.zip.GZIPInputStream(inputStream);
        var tarInputStream = new TarArchiveInputStream(gzipInputStream)) {
      org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
      while ((entry = tarInputStream.getNextEntry()) != null) {
        if (!entry.isFile()
            || !libraryName.equals(entry.getName())) {
          continue;
        }
        if (entry.getSize() <= 0 || entry.getSize() > HttpDownloadClient.MAX_ARCHIVE_BYTES) {
          throw new IOException("sqlite-vec ライブラリのサイズが不正です");
        }
        Path temporary = Files.createTempFile(installDir, "sqlite-vec-", ".tmp");
        try {
          Files.copy(tarInputStream, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          Files.move(temporary, libraryPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
          Files.deleteIfExists(temporary);
        }
        return;
      }
      throw new IOException("sqlite-vec archive 内にライブラリがありません: " + libraryPath);
    }
  }

  /** 実行中の OS とアーキテクチャを取得する。 */
  @FunctionalInterface
  interface PlatformSupplier {
    /** 現在の実行環境を返す。 */
    SqliteVecPlatform get();
  }

  /** 取得を外部の接続先へ依頼する窓口。 */
  interface DownloadClient {
    /** 文書検索用の保存基盤で用いる取得を返す。 */
    byte[] download(URI uri) throws IOException;
  }

  /** HTTPS でアーカイブを取得し、転送先と応答サイズを制限する。 */
  private static final class HttpDownloadClient implements DownloadClient {

    /** 圧縮済みアーカイブと展開対象のサイズ上限。 */
    private static final int MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;

    /** リダイレクト先のホストを確認するため、自動追従は使用しない。 */
    private final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public byte[] download(URI uri) throws IOException {
      URI destination = uri;
      try {
        for (int redirects = 0; redirects <= 3; redirects++) {
          validateDestination(uri, destination);
          HttpRequest request =
              HttpRequest.newBuilder(destination).timeout(Duration.ofSeconds(30)).GET().build();
          HttpResponse<byte[]> response =
              httpClient.send(
                  request,
                  HttpResponse.BodyHandlers.limiting(
                      HttpResponse.BodyHandlers.ofByteArray(), MAX_ARCHIVE_BYTES));
          int status = response.statusCode();
          if (status >= 200 && status < 300) return response.body();
          if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
            throw new IOException("sqlite-vec download HTTP " + status);
          }
          String location =
              response.headers().firstValue("Location")
                  .orElseThrow(() -> new IOException("sqlite-vec redirect に Location がありません"));
          destination = destination.resolve(location);
        }
        throw new IOException("sqlite-vec redirect の回数が上限を超えました");
      } catch (IllegalArgumentException e) {
        throw new IOException("sqlite-vec download URL が不正です", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("sqlite-vec ダウンロードが中断されました", e);
      }
    }

    /** 明示的に設定した HTTPS origin と GitHub Releases の配信先のみを許可する。 */
    private void validateDestination(URI requested, URI destination) throws IOException {
      String host = destination.getHost();
      if (!"https".equalsIgnoreCase(destination.getScheme())
          || destination.getUserInfo() != null
          || host == null
          || (destination.getPort() != -1 && destination.getPort() != 443)) {
        throw new IOException("sqlite-vec download は標準ポートの HTTPS URL が必要です");
      }
      if (!host.equalsIgnoreCase(requested.getHost())
          && !("github.com".equalsIgnoreCase(requested.getHost())
              && "release-assets.githubusercontent.com".equalsIgnoreCase(host))) {
        throw new IOException("sqlite-vec redirect の接続先は許可されていません");
      }
    }
  }
}
