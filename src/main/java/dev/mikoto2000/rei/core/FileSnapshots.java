package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 共通の実行基盤に必要なファイル状態の記録一覧を扱う。
 *
 * <p>Request-local, bounded immutable bytes. No metadata-only cross-request cache.
 */
final class FileSnapshots {
  /** 最大ファイルバイト数。 */
  static final int MAX_FILE_BYTES = 1024 * 1024;

  /** 最大要求バイト数。 */
  static final int MAX_REQUEST_BYTES = 8 * MAX_FILE_BYTES;

  /** 最大ファイル一覧。 */
  static final int MAX_FILES = 1024;

  /** 共通の実行基盤に必要な読み取り元を扱う。 */
  @FunctionalInterface
  interface Reader {
    /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
    byte[] read(Path path) throws IOException;
  }

  /**
   * 共通の実行基盤で受け渡す状態の記録を表す。
   *
   * @param path パス
   * @param version 形式のバージョン
   * @param bytes バイト数
   */
  record Snapshot(Path path, String version, byte[] bytes) {
    /** 受け取った情報からSnapshotを作成する。 */
    Snapshot {
      bytes = bytes.clone();
    }

    /** 共通の実行基盤で用いるバイト数を返す。 */
    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  /** ファイル一覧。 */
  private final Map<Path, Snapshot> files = new LinkedHashMap<>();

  private final Map<List<String>, List<String>> decoded = new HashMap<>();

  @FunctionalInterface
  interface Decoder {
    List<String> decode() throws IOException;
  }

  synchronized List<String> lines(Snapshot snapshot, String charset, Decoder decoder)
      throws IOException {
    var key =
        List.of(snapshot.path().toString(), snapshot.version(), Objects.toString(charset, ""));
    var value = decoded.get(key);
    if (value == null) {
      value = List.copyOf(decoder.decode());
      decoded.put(key, value);
    }
    return value;
  }

  /** 読み取り元。 */
  private final Reader reader;

  /** バイト数。 */
  private int bytes;

  /** 条件ごとに列挙した対象情報。 */
  private final Map<List<String>, List<String>> inventories = new HashMap<>();

  /** 共通の実行基盤に必要な保有情報を扱う。 */
  @FunctionalInterface
  interface Inventory {
    /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
    List<String> list() throws IOException, InterruptedException;
  }

  /** 共通の実行基盤で用いる保有情報を返す。 */
  synchronized List<String> inventory(String base, Inventory loader)
      throws IOException, InterruptedException {
    return inventory(List.of(base), loader);
  }

  /** 共通の実行基盤で用いる保有情報を返す。 */
  synchronized List<String> inventory(List<String> key, Inventory loader)
      throws IOException, InterruptedException {
    key = List.copyOf(key);
    var cached = inventories.get(key);
    if (cached != null) return cached;
    var paths = loader.list().stream().distinct().sorted().toList();
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (paths.size() > MAX_FILES)
      throw new IOException("File inventory limit reached (1024); narrow baseDir/globs");
    if (paths.stream().anyMatch(path -> path.length() > 1024))
      throw new IOException("Inventory path limit reached (1024 characters)");
    inventories.put(key, paths);
    return paths;
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  FileSnapshots() {
    this(
        path -> {
          try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return in.readNBytes(MAX_FILE_BYTES + 1);
          }
        });
  }

  /** 指定された値や利用先を使って、FileSnapshotsを初期化する。 */
  FileSnapshots(Reader reader) {
    this.reader = reader;
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  synchronized Snapshot get(Path root, Path input) throws IOException {
    ReadingMetrics.add("fileRequests", 1);
    RunCancellation.propagate(null);
    Path path = resolve(root, input);
    var existing = files.get(path);
    if (existing != null) return existing;
    if (files.size() >= MAX_FILES) throw new IOException("Snapshot file limit reached");
    var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (before.size() > MAX_FILE_BYTES || before.size() > MAX_REQUEST_BYTES - bytes)
      throw new IOException("Snapshot byte limit reached");
    ReadingMetrics.add("physicalReads", 1);
    var content = reader.read(path);
    var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    RunCancellation.propagate(null);
    if (!before.lastModifiedTime().equals(after.lastModifiedTime())
        || !before.creationTime().equals(after.creationTime())
        || before.size() != after.size()
        || !Objects.equals(before.fileKey(), after.fileKey())
        || after.size() != content.length)
      throw new IOException("File changed during snapshot read; retry the request");
    if (content.length > MAX_FILE_BYTES || content.length > MAX_REQUEST_BYTES - bytes)
      throw new IOException("Snapshot byte limit reached");
    var snapshot = new Snapshot(path, hash(content), content);
    files.put(path, snapshot);
    bytes += content.length;
    return snapshot;
  }

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  static Path resolve(Path directory, Path input) throws IOException {
    var path = resolveInventoryPath(directory, input);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
      throw new NoSuchFileException(path.toString());
    return path;
  }

  /** 指定や設定に応じた対象を利用するため、保有情報パスを確定する。 */
  static Path resolveInventoryPath(Path directory, Path input) throws IOException {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (input.toString().length() > 1024) throw new IOException("Path exceeds 1024 characters");
    var root = directory.toRealPath();
    var path = (input.isAbsolute() ? input : root.resolve(input)).normalize();
    if (!path.startsWith(root) || RepositoryMapService.sensitive(root.relativize(path)))
      throw new IOException("Excluded or outside Project path");
    var owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    if (owner != null
        && owner.requestSource()
            == dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.MCP) {
      var captured = owner.projectRoot().toRealPath();
      var data = dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current();
      if (Files.exists(data)) data = data.toRealPath();
      if (owner.mode() != dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY
          || !path.startsWith(captured)
          || path.startsWith(data)
          || mcpPrivate(captured.relativize(path)))
        throw new IOException("MCP excludes private or outside Project paths");
    }
    var cursor = root;
    for (var part : root.relativize(path)) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor)
          || Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().equals(cursor))
        throw new IOException("Linked or aliased paths are unsupported");
    }
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !path.toRealPath().startsWith(root))
      throw new IOException("Outside Project path");
    return path;
  }

  /** 共通の実行基盤において、MCP非公開に該当するか判定する。 */
  private static boolean mcpPrivate(Path relative) {
    for (var part : relative) {
      String name = part.toString().toLowerCase(Locale.ROOT);
      if (Set.of(
                  ".rei",
                  ".ssh",
                  ".gnupg",
                  ".kube",
                  ".docker",
                  "auth.json",
                  "token.json",
                  "tokens.json",
                  "api_keys.json",
                  "api-keys.json")
              .contains(name)
          || name.matches(".*\\.(?:db(?:-wal|-shm)?|sqlite3?)")) return true;
    }
    return false;
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  static String hash(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
