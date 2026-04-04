package dev.mikoto2000.rei.core.sqlitevec;

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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.mikoto2000.rei.core.configuration.SqliteVecProperties;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SqliteVecInstaller {

  private final SqliteVecProperties properties;
  private final PlatformSupplier platformSupplier;
  private final SqliteVecAssetResolver assetResolver;
  private final JsonMapper objectMapper;
  private final DownloadClient downloadClient;

  @Autowired
  public SqliteVecInstaller(
      SqliteVecProperties properties,
      PlatformDetector platformDetector,
      SqliteVecAssetResolver assetResolver,
      JsonMapper objectMapper) {
    this(properties, platformDetector::detect, assetResolver, objectMapper, new HttpDownloadClient());
  }

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
    Path installDir = Path.of(properties.getCacheDir()).resolve(properties.getVersion()).resolve(platform.id());
    Path libraryPath = installDir.resolve(artifact.libraryFileName());
    if (Files.exists(libraryPath)) {
      return libraryPath;
    }
    if (!properties.isAutoDownload()) {
      throw new IllegalStateException("sqlite-vec extension が未配置で、自動ダウンロードも無効です: " + libraryPath);
    }

    try {
      Files.createDirectories(installDir);
      Manifest manifest = objectMapper.readValue(downloadClient.download(manifestUri()), Manifest.class);
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

  private URI manifestUri() {
    return URI.create("%s/v%s/sqlite-dist-manifest.json".formatted(properties.getReleaseBaseUrl(), properties.getVersion()));
  }

  private URI assetUri(String assetName) {
    return URI.create("%s/v%s/%s".formatted(properties.getReleaseBaseUrl(), properties.getVersion(), assetName));
  }

  private void verifyChecksum(byte[] bytes, String expectedChecksum) {
    String actualChecksum;
    try {
      actualChecksum = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 が利用できません", e);
    }
    if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
      throw new IllegalStateException("sqlite-vec archive の checksum が一致しません");
    }
  }

  private void extractArchive(byte[] archiveBytes, Path installDir) throws IOException {
    try (InputStream inputStream = new java.io.ByteArrayInputStream(archiveBytes);
        var gzipInputStream = new java.util.zip.GZIPInputStream(inputStream);
        var tarInputStream = new TarArchiveInputStream(gzipInputStream)) {
      org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
      while ((entry = tarInputStream.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        Path outputPath = installDir.resolve(Path.of(entry.getName()).getFileName().toString()).normalize();
        if (!outputPath.startsWith(installDir.normalize())) {
          throw new IOException("sqlite-vec archive に不正な entry があります: " + entry.getName());
        }
        Files.copy(tarInputStream, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  @FunctionalInterface
  interface PlatformSupplier {
    SqliteVecPlatform get();
  }

  interface DownloadClient {
    byte[] download(URI uri) throws IOException;
  }

  private static final class HttpDownloadClient implements DownloadClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public byte[] download(URI uri) throws IOException {
      HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
      try {
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("ダウンロードが中断されました: " + uri, e);
      }
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record Manifest(List<ManifestArtifact> artifacts) {
    private ManifestArtifact findArtifact(String assetName) {
      return artifacts.stream()
          .filter(artifact -> assetName.equals(artifact.name()))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("sqlite-vec asset が manifest にありません: " + assetName));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ManifestArtifact(
      String name,
      @JsonProperty("checksum_sha256") String checksumSha256
  ) {}
}
