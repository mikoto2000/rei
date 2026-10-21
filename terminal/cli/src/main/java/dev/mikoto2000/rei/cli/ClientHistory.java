package dev.mikoto2000.rei.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/**
 * 軽量CLIでの対話に必要なクライアント履歴を扱う。
 *
 * <p>Each process owns one bounded history file. History is optional and never a shared writer.
 */
public final class ClientHistory implements AutoCloseable {
  /** ファイル。 */
  private final Path file;

  /** チャネル。 */
  private final java.nio.channels.FileChannel channel;

  /** 排他制御。 */
  private final java.nio.channels.FileLock lock;

  /** 項目一覧。 */
  private final ArrayDeque<String> entries = new ArrayDeque<>();

  /** 有効状態。 */
  private boolean enabled = true;

  /** 認証キー。 */
  private final String authenticationKey;

  /** 軽量CLIでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ClientHistory(Path directory) throws IOException {
    this(directory, System.getenv("REI_API_KEY"));
  }

  /** 指定された値や利用先を使って、ClientHistoryを初期化する。 */
  ClientHistory(Path directory, String authenticationKey) throws IOException {
    this.authenticationKey = authenticationKey;
    directory = directory.toAbsolutePath().normalize();
    for (Path parent = directory; parent != null; parent = parent.getParent())
      if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
        var attributes =
            Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther())
          throw new IOException("Unsafe history directory");
      }
    Files.createDirectories(directory);
    try (var candidates = Files.list(directory)) {
      var recent =
          candidates
              .filter(path -> path.getFileName().toString().matches("cli-[a-f0-9-]{36}\\.history"))
              .limit(10000)
              .sorted(Comparator.comparingLong(ClientHistory::modified).reversed())
              .toList();
      for (Path prior : recent) {
        var attributes =
            Files.readAttributes(prior, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
            || attributes.isSymbolicLink()
            || attributes.isOther()
            || attributes.size() > 1048576) continue;
        try (var input =
            java.nio.channels.FileChannel.open(
                prior,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
          try (var available = input.tryLock()) {
            if (available == null) continue;
            var bytes = java.nio.ByteBuffer.allocate((int) attributes.size());
            while (bytes.hasRemaining() && input.read(bytes) >= 0) {}
            for (String line : new String(bytes.array(), StandardCharsets.UTF_8).split("\n"))
              if (!line.isEmpty() && accepts(line)) entries.addLast(line);
            while (entries.size() > 1000) entries.removeFirst();
            break;
          }
        } catch (java.nio.channels.OverlappingFileLockException active) {
          /* Another CLI owns this file. */
        }
      }
    }
    file = Files.createFile(directory.resolve("cli-" + UUID.randomUUID() + ".history"));
    var acl =
        Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl != null)
      acl.setAcl(
          List.of(
              AclEntry.newBuilder()
                  .setType(AclEntryType.ALLOW)
                  .setPrincipal(acl.getOwner())
                  .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                  .build()));
    else Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    channel =
        java.nio.channels.FileChannel.open(
            file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    lock = channel.lock();
  }

  /** 軽量CLIでの対話で用いる更新を返す。 */
  private static long modified(Path file) {
    try {
      return Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
    } catch (IOException error) {
      return 0;
    }
  }

  /** 軽量CLIでの対話で用いる項目一覧を返す。 */
  public List<String> entries() {
    return List.copyOf(entries);
  }

  /** 軽量CLIでの対話で用いるファイルを返す。 */
  public Path file() {
    return file;
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public void enabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  public static boolean safe(String line) {
    String lower = line.toLowerCase(Locale.ROOT);
    return line.length() <= 16384
        && !line.contains("\n")
        && !line.contains("\r")
        && !lower.startsWith("/history")
        && !lower.startsWith("/input-history")
        && !lower.startsWith("/config")
        && !lower.contains("authorization:")
        && !lower.contains("bearer ")
        && !lower.contains("api_key")
        && !lower.contains("api-key")
        && !lower.contains("password")
        && !lower.contains("secret")
        && !lower.contains("token=")
        && !lower.contains("sk-");
  }

  /** 後から経緯を確認できるよう、指定された内容を記録する。 */
  public void record(String line) throws IOException {
    if (!enabled || !accepts(line)) return;
    entries.addLast(line);
    while (entries.size() > 1000) entries.removeFirst();
    byte[] bytes = (String.join("\n", entries) + "\n").getBytes(StandardCharsets.UTF_8);
    while (bytes.length > 1048576) {
      entries.removeFirst();
      bytes = (String.join("\n", entries) + "\n").getBytes(StandardCharsets.UTF_8);
    }
    channel.position(0);
    channel.truncate(0);
    var buffer = java.nio.ByteBuffer.wrap(bytes);
    while (buffer.hasRemaining()) channel.write(buffer);
    channel.force(true);
  }

  /** 軽量CLIでの対話で判断に用いるacceptsの条件を確認する。 */
  public boolean accepts(String line) {
    return safe(line)
        && (authenticationKey == null
            || authenticationKey.isBlank()
            || !line.contains(authenticationKey));
  }

  /** 軽量CLIでの対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
