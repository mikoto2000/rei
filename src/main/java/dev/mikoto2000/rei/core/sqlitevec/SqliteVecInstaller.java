package dev.mikoto2000.rei.core.sqlitevec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 文書検索用の保存基盤に必要なSQLiteベクトルInstallerを扱う。 */
@Component
public class SqliteVecInstaller {

  /** この機能の動作設定。 */
  private final SqliteVecProperties properties;

  /** 実行環境取得元。 */
  private final PlatformSupplier platformSupplier;

  /** 素材対象の解決。 */
  private final SqliteVecAssetResolver assetResolver;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper objectMapper;

  /** 取得クライアント。 */
  private final DownloadClient downloadClient;

  /** 文書検索用の保存基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @Autowired
  public SqliteVecInstaller(
      SqliteVecProperties properties,
      PlatformDetector platformDetector,
      SqliteVecAssetResolver assetResolver,
      JsonMapper objectMapper) {
    this(
        properties,
        platformDetector::detect,
        assetResolver,
        objectMapper,
        new HttpDownloadClient());
  }

  /** 指定された値や利用先を使って、SqliteVecInstallerを初期化する。 */
  SqliteVecInstaller(
      SqliteVecProperties properties,
      PlatformSupplier platformSupplier,
      SqliteVecAssetResolver assetResolver,
      JsonMapper objectMapper,
      DownloadClient downloadClient) {
    this.properties = properties;
    this.platformSupplier = platformSupplier;
    this.assetResolver = assetResolver;
    this.objectMapper = objectMapper;
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

    try {
      Files.createDirectories(installDir);
      Manifest manifest =
          objectMapper.readValue(downloadClient.download(manifestUri()), Manifest.class);
      ManifestArtifact manifestArtifact = manifest.findArtifact(artifact.assetName());
      byte[] archiveBytes = downloadClient.download(assetUri(artifact.assetName()));
      verifyChecksum(archiveBytes, manifestArtifact.checksumSha256());
      extractArchive(archiveBytes, installDir);
      if (!Files.exists(libraryPath)) {
        throw new IllegalStateException("sqlite-vec extension の展開に失敗しました: " + libraryPath);
      }
      return libraryPath;
    } catch (IOException e) {
      throw new IllegalStateException("sqlite-vec extension の準備に失敗しました", e);
    }
  }

  /** 文書検索用の保存基盤で用いるmanifestURIを返す。 */
  private URI manifestUri() {
    return URI.create(
        "%s/v%s/sqlite-dist-manifest.json"
            .formatted(properties.getReleaseBaseUrl(), properties.getVersion()));
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

  /** 後続の判断に必要な情報を得るため、保管を抽出する。 */
  private void extractArchive(byte[] archiveBytes, Path installDir) throws IOException {
    try (InputStream inputStream = new java.io.ByteArrayInputStream(archiveBytes);
        var gzipInputStream = new java.util.zip.GZIPInputStream(inputStream);
        var tarInputStream = new TarArchiveInputStream(gzipInputStream)) {
      org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
      while ((entry = tarInputStream.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        Path outputPath =
            installDir.resolve(Path.of(entry.getName()).getFileName().toString()).normalize();
        if (!outputPath.startsWith(installDir.normalize())) {
          throw new IOException("sqlite-vec archive に不正な entry があります: " + entry.getName());
        }
        Files.copy(tarInputStream, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /** 文書検索用の保存基盤に必要な実行環境取得元を扱う。 */
  @FunctionalInterface
  interface PlatformSupplier {
    /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
    SqliteVecPlatform get();
  }

  /** 取得を外部の接続先へ依頼する窓口。 */
  interface DownloadClient {
    /** 文書検索用の保存基盤で用いる取得を返す。 */
    byte[] download(URI uri) throws IOException;
  }

  /** HTTP通信取得を外部の接続先へ依頼する窓口。 */
  private static final class HttpDownloadClient implements DownloadClient {

    /** 外部情報を取得するHTTP通信の窓口。 */
    private final HttpClient httpClient =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** 文書検索用の保存基盤で用いる取得を返す。 */
    @Override
    public byte[] download(URI uri) throws IOException {
      HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
      try {
        HttpResponse<byte[]> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
      } catch (InterruptedException e) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        throw new IOException("ダウンロードが中断されました: " + uri, e);
      }
    }
  }

  /**
   * 文書検索用の保存基盤で受け渡すManifestを表す。
   *
   * @param artifacts 成果物一覧
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Manifest(List<ManifestArtifact> artifacts) {
    /** 対象を特定できるよう、成果物を探す。 */
    private ManifestArtifact findArtifact(String assetName) {
      return artifacts.stream()
          .filter(artifact -> assetName.equals(artifact.name()))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("sqlite-vec asset が manifest にありません: " + assetName));
    }
  }

  /**
   * 文書検索用の保存基盤で受け渡すManifest成果物を表す。
   *
   * @param name 名前
   * @param checksumSha256 検査値内容の同一性を照合するSHA-256ハッシュ
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ManifestArtifact(
      String name, @JsonProperty("checksum_sha256") String checksumSha256) {}
}
