package dev.mikoto2000.rei.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * バックエンド接続先の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>The Backend writes only while holding its storage lease; launchers only read.
 */
public final class BackendEndpointStore {
  /** バイト数の上限。 */
  private static final int MAX_BYTES = 16384;

  /** ディレクトリ。 */
  private final Path directory;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper();

  /** 指定された値や利用先を使って、BackendEndpointStoreを初期化する。 */
  public BackendEndpointStore(Path root) {
    directory = root.toAbsolutePath().normalize().resolve(".storage");
  }

  /** 起動環境の準備で用いる接続先を返す。 */
  private Path endpoint() {
    return directory.resolve("backend-endpoint.json");
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public Optional<BackendEndpoint> read() throws IOException {
    if (Files.notExists(endpoint(), LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    return Optional.of(json.readValue(bounded(endpoint()), BackendEndpoint.class));
  }

  /** 起動環境の準備で用いる保存識別子を返す。 */
  public String storageId() throws IOException {
    safeDirectory();
    Path file = directory.resolve("storage-id");
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS))
      writeAtomic(
          file, UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String id = new String(bounded(file), java.nio.charset.StandardCharsets.UTF_8);
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid persistent storage identity", invalid);
    }
    return id;
  }

  /**
   * 必要な内容を参照できるよう、保存識別子を読み取る。
   *
   * <p>Launcher identity check never creates or repairs persistent metadata.
   */
  public Optional<String> readStorageId() throws IOException {
    Path file = directory.resolve("storage-id");
    if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    String id = new String(bounded(file), java.nio.charset.StandardCharsets.UTF_8);
    try {
      if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid persistent storage identity");
    }
    return Optional.of(id);
  }

  /** 状態や結果を購読先へ通知する。 */
  public void publish(BackendEndpoint value) throws IOException {
    safeDirectory();
    if (!storageId().equals(value.storageId())) throw new IOException("Storage identity mismatch");
    writeAtomic(endpoint(), json.writeValueAsBytes(value));
  }

  /** 対象を管理から外すため、自分が所有する対象を取り除く。 */
  public void removeOwned(String instanceId) throws IOException {
    var current = read();
    if (current.isPresent() && current.get().instanceId().equals(instanceId))
      Files.delete(endpoint());
  }

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private byte[] bounded(Path file) throws IOException {
    safePath(file);
    try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw new IOException("Backend metadata capacity exceeded");
      return bytes;
    }
  }

  /** 起動環境の準備に関する安全性ディレクトリを処理する。 */
  private void safeDirectory() throws IOException {
    safePath(directory);
    Files.createDirectories(directory);
  }

  /** 起動環境の準備に関する安全性パスを処理する。 */
  private static void safePath(Path path) throws IOException {
    for (Path part = path; part != null; part = part.getParent()) {
      if (Files.notExists(part, LinkOption.NOFOLLOW_LINKS)) continue;
      var attributes =
          Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || attributes.isOther())
        throw new IOException("Unsafe discovery metadata path");
    }
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Unsupported discovery path");
  }

  /** 指定された内容を保存先へ反映するため、一括反映を書き込む。 */
  private void writeAtomic(Path destination, byte[] bytes) throws IOException {
    safePath(destination);
    Path temporary = Files.createTempFile(directory, ".endpoint-", ".tmp");
    try {
      restrict(temporary);
      try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        var buffer = java.nio.ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      // Do not silently fall back to a non-atomic replacement.
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** 起動環境の準備に関するrestrictを処理する。 */
  private static void restrict(Path file) throws IOException {
    var acl =
        Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl != null) {
      var entry =
          AclEntry.newBuilder()
              .setType(AclEntryType.ALLOW)
              .setPrincipal(acl.getOwner())
              .setPermissions(EnumSet.allOf(AclEntryPermission.class))
              .build();
      acl.setAcl(List.of(entry));
    } else {
      var posix =
          Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (posix == null) throw new IOException("Filesystem cannot protect backend metadata");
      posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
    }
  }
}
