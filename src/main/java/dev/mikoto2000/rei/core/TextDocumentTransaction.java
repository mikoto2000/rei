package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 共通の実行基盤に必要なテキスト文書一連の更新を扱う。
 *
 * <p>Logical all-or-rollback text application. No filesystem promises atomic visibility of multiple
 * paths.
 */
public final class TextDocumentTransaction {
  /**
   * 共通の実行基盤で受け渡す変更を表す。
   *
   * @param path パス
   * @param before 変更前
   * @param after 変更後
   */
  public record Change(String path, String before, String after) {}

  /**
   * 共通の実行基盤で受け渡す段階を表す。
   *
   * @param path パス
   * @param temporary 一時的な
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param baseline 比較の基準
   */
  public record Stage(String path, String temporary, String sha256, boolean baseline) {}

  /**
   * 共通の実行基盤で受け渡す最終結果を表す。
   *
   * @param status 状態
   * @param warnings 注意事項
   */
  public record Outcome(String status, List<String> warnings) {}

  /**
   * 共通の実行基盤で受け渡す要求内容の照合値を表す。
   *
   * @param available 利用可能
   * @param exists 存在
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   */
  public record Fingerprint(boolean available, boolean exists, String sha256) {}

  /** 共通の実行基盤に必要なMoveを扱う。 */
  @FunctionalInterface
  public interface Move {
    /** 指定された内容へ切り替えるため、Moveを置き換える。 */
    void replace(Path staged, Path target) throws IOException;
  }

  /** 共通の実行基盤に必要なJournalを扱う。 */
  @FunctionalInterface
  public interface Journal {
    /** 後から参照できるよう、指定された情報を保存する。 */
    void save(String phase, List<Stage> stages) throws IOException;
  }

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private TextDocumentTransaction() {}

  /** 共通の実行基盤に関するpreflightを処理する。 */
  public static void preflight(Path root, String id, List<Change> changes) throws IOException {
    validate(root, id, changes);
    for (var stage : planned(changes, id)) path(root, stage.temporary());
    for (var stage : planned(changes, id + "-rollback")) path(root, stage.temporary());
  }

  /** 指定された内容へ切り替えるため、テキスト文書一連の更新を置き換える。 */
  public static void replace(Path staged, Path target) throws IOException {
    if (staged == null) Files.delete(target);
    else
      Files.move(
          staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * 指定された内容を保存先へ反映するため、単一を書き込む。
   *
   * <p>Existing staging and permission preservation, with no non-atomic fallback.
   */
  public static void writeSingle(Path target, String before, String after) throws IOException {
    writeSingle(target, before, after, TextDocumentTransaction::replace);
  }

  /** 指定された内容を保存先へ反映するため、単一を書き込む。 */
  static void writeSingle(Path target, String before, String after, Move move) throws IOException {
    text(before);
    text(after);
    writeBytes(target, before.getBytes(StandardCharsets.UTF_8), after.getBytes(StandardCharsets.UTF_8), move);
  }

  /** Encoded legacy edits use the same staging, permission and atomic-publication boundary. */
  static void writeBytes(Path target, byte[] before, byte[] after, Move move) throws IOException {
    Path root = target.toAbsolutePath().getParent().toRealPath();
    String name = target.getFileName().toString();
    if (!path(root, name).equals(target.toAbsolutePath().normalize()))
      throw new IOException("Aliased text target rejected");
    if (!bytesMatch(target, before)) throw new IOException("Baseline changed before staging");
    Path temporary = path(root, temporary(name, UUID.randomUUID().toString(), 0));
    try {
      RunCancellation.propagate(null);
      stageBytes(temporary, target, after);
      RunCancellation.propagate(null);
      if (!bytesMatch(target, before)) throw new IOException("Baseline changed before atomic publication");
      if (!bytesMatch(temporary, after)) throw new IOException("Staged content changed");
      move.replace(temporary, target);
      if (!bytesMatch(target, after)) throw new IOException("Published content requires inspection");
    } catch (IOException | RuntimeException failure) {
      try { cleanupBytes(temporary, after); }
      catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
    cleanupBytes(temporary, after);
  }

  private static boolean bytesMatch(Path path, byte[] expected) throws IOException {
    try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      return Arrays.equals(expected, stream.readNBytes(expected.length + 1));
    }
  }

  private static void cleanupBytes(Path temporary, byte[] expected) throws IOException {
    if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) return;
    if (!bytesMatch(temporary, expected)) throw new IOException("Staging content changed; preserve it");
    Files.delete(temporary);
  }

  /** 指定された内容を、現在の対象へ反映する。 */
  public static Outcome apply(
      Path root, String id, List<Change> changes, Journal journal, Move move) throws IOException {
    validate(root, id, changes);
    if (!matches(root, changes, false))
      return new Outcome("STALE", List.of("Baseline changed before staging; no target write"));
    var stages = new ArrayList<>(planned(changes, id));
    stages.addAll(planned(changes, id + "-rollback"));
    journal.save("STAGING", List.copyOf(stages));
    try {
      for (var stage : stages) {
        RunCancellation.propagate(null);
        var change =
            changes.stream()
                .filter(item -> item.path().equals(stage.path()))
                .findFirst()
                .orElseThrow();
        stage(root, stage, stage.baseline() ? change.before() : change.after());
      }
      if (!matches(root, changes, false)) {
        cleanup(root, stages);
        return new Outcome(
            "STALE", List.of("Baseline changed before publication; no target write"));
      }
      for (var change : changes) {
        RunCancellation.propagate(null);
        journal.save("PUBLISHING", stages);
        var target = path(root, change.path());
        if (!Objects.equals(read(root, change.path()), change.before()))
          throw new IOException("Baseline changed during publication");
        var stage =
            stages.stream()
                .filter(item -> !item.baseline() && item.path().equals(change.path()))
                .findFirst()
                .orElse(null);
        Path temporary = stage == null ? null : path(root, stage.temporary());
        if (stage != null && !stage.sha256().equals(hash(read(root, stage.temporary()))))
          throw new IOException("Staged text changed");
        move.replace(temporary, target);
        if (!Objects.equals(read(root, change.path()), change.after()))
          throw new IOException("Publication requires inspection");
      }
      if (!matches(root, changes, true)) throw new IOException("Final text set differs");
      journal.save("VERIFIED", List.copyOf(stages));
      return new Outcome("APPLIED", List.of());
    } catch (IOException | RuntimeException failure) {
      boolean interrupted = Thread.interrupted();
      Outcome recovered;
      try {
        recovered = rollback(root, id, changes, stages, journal);
      } catch (IOException | RuntimeException recovery) {
        failure.addSuppressed(recovery);
        recovered =
            new Outcome("UNKNOWN", List.of("Rollback or staging cleanup requires inspection"));
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
      RunCancellation.propagate(failure);
      return recovered;
    }
  }

  /** 共通の実行基盤で用いる取り消しを返す。 */
  public static Outcome rollback(
      Path root, String id, List<Change> changes, List<Stage> previous, Journal journal)
      throws IOException {
    validate(root, id, changes);
    validateStages(id, changes, previous);
    var recovery = planned(changes, id + "-rollback");
    var all = new ArrayList<>(previous);
    all.addAll(recovery);
    journal.save("ROLLBACK", List.copyOf(all));
    boolean complete = true;
    for (int i = changes.size() - 1; i >= 0; i--) {
      var change = changes.get(i);
      String current;
      try {
        current = read(root, change.path());
      } catch (IOException | IllegalArgumentException unavailable) {
        complete = false;
        continue;
      }
      if (Objects.equals(current, change.before())) continue;
      if (!Objects.equals(current, change.after())) {
        complete = false;
        continue;
      }
      Path target = path(root, change.path());
      try {
        if (change.before() == null) {
          if (!Objects.equals(read(root, change.path()), change.after())) {
            complete = false;
            continue;
          }
          Files.delete(target);
        } else {
          String name = temporary(change.path(), id + "-rollback", i);
          var restore = new Stage(change.path(), name, hash(change.before()), true);
          Path temporary = path(root, name);
          if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            if (!Objects.equals(read(root, name), change.before())) {
              complete = false;
              continue;
            }
          } else stage(root, restore, change.before());
          if (!Objects.equals(read(root, change.path()), change.after())) {
            complete = false;
            continue;
          }
          replace(temporary, target);
        }
      } catch (IOException | IllegalArgumentException unavailable) {
        complete = false;
      }
    }
    if (!matches(root, changes, false)) complete = false;
    try {
      cleanup(root, all);
    } catch (IOException | IllegalArgumentException unavailable) {
      complete = false;
    }
    journal.save(complete ? "ROLLED_BACK" : "UNKNOWN", complete ? List.of() : List.copyOf(all));
    return new Outcome(
        complete ? "ROLLED_BACK" : "UNKNOWN",
        List.of(
            complete
                ? "Original text set restored; consumed attempt is not replayable"
                : "Some current paths differ from both saved versions; preserve external changes and inspect"));
  }

  /** 共通の実行基盤で用いるplannedを返す。 */
  private static List<Stage> planned(List<Change> changes, String id) {
    var stages = new ArrayList<Stage>();
    boolean baseline = id.endsWith("-rollback");
    for (int i = 0; i < changes.size(); i++) {
      var change = changes.get(i);
      String text = baseline ? change.before() : change.after();
      if (text != null)
        stages.add(new Stage(change.path(), temporary(change.path(), id, i), hash(text), baseline));
    }
    return List.copyOf(stages);
  }

  /** 共通の実行基盤で用いる一時的なを返す。 */
  private static String temporary(String path, String id, int index) {
    int slash = path.lastIndexOf('/');
    return (slash < 0 ? "" : path.substring(0, slash + 1))
        + ".rei-document-"
        + id
        + "-"
        + index
        + ".tmp";
  }

  /** 共通の実行基盤に関する段階を処理する。 */
  private static void stage(Path root, Stage stage, String text) throws IOException {
    Path temporary = path(root, stage.temporary()), target = path(root, stage.path());
    stageBytes(temporary, target, text.getBytes(StandardCharsets.UTF_8));
  }

  private static void stageBytes(Path temporary, Path target, byte[] bytes) throws IOException {
    try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      var buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    }
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) copyPermissions(target, temporary);
  }

  /** 共通の実行基盤に関する複製権限一覧を処理する。 */
  private static void copyPermissions(Path source, Path destination) throws IOException {
    var posix =
        Files.getFileAttributeView(source, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      var attributes = posix.readAttributes();
      var target =
          Files.getFileAttributeView(
              destination, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      target.setPermissions(attributes.permissions());
      target.setGroup(attributes.group());
      target.setOwner(attributes.owner());
    }
    var acl =
        Files.getFileAttributeView(source, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl != null) {
      var target =
          Files.getFileAttributeView(
              destination, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      target.setAcl(acl.getAcl());
      if (!target.getOwner().equals(acl.getOwner())) target.setOwner(acl.getOwner());
    }
  }

  /** 共通の実行基盤に関する後片付けを処理する。 */
  private static void cleanup(Path root, List<Stage> stages) throws IOException {
    var seen = new HashSet<String>();
    for (var stage : stages) {
      if (!seen.add(stage.temporary())) continue;
      if (stage.temporary() == null
          || !stage
              .temporary()
              .substring(stage.temporary().lastIndexOf('/') + 1)
              .startsWith(".rei-document-")) throw new IOException("Unowned staging path");
      Path path = path(root, stage.temporary());
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
      if (!stage.sha256().equals(hash(read(root, stage.temporary()))))
        throw new IOException("Staging content changed; preserve it");
      Files.delete(path);
    }
  }

  /** 共通の実行基盤に関する後片付け自分が所有する対象を処理する。 */
  public static void cleanupOwned(Path root, String id, List<Change> changes, List<Stage> stages)
      throws IOException {
    validate(root, id, changes);
    validateStages(id, changes, stages);
    cleanup(root, stages);
  }

  /** 利用条件に合わない指定を受け付けないよう、段階一覧を検証する。 */
  private static void validateStages(String id, List<Change> changes, List<Stage> stages)
      throws IOException {
    var allowed = new HashSet<>(planned(changes, id));
    allowed.addAll(planned(changes, id + "-rollback"));
    if (stages == null
        || stages.size() > 128
        || stages.stream().anyMatch(stage -> !allowed.contains(stage)))
      throw new IOException("Staging journal differs from saved text set");
  }

  /** 指定された条件と対象が一致するかを判定する。 */
  public static boolean matches(Path root, List<Change> changes, boolean after) throws IOException {
    for (var change : changes)
      if (!Objects.equals(read(root, change.path()), after ? change.after() : change.before()))
        return false;
    return true;
  }

  /** 共通の実行基盤で用いるパスを返す。 */
  public static Path path(Path root, String name) throws IOException {
    if (root == null
        || !Files.isDirectory(root)
        || !root.toRealPath().equals(root)
        || name == null
        || name.isBlank()
        || name.length() > 1024
        || name.contains("\\")
        || name.contains(":")
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Canonical Project root and relative text path required");
    Path relative = Path.of(name);
    if (relative.isAbsolute()
        || relative.getRoot() != null
        || Arrays.stream(name.split("/", -1))
            .anyMatch(part -> part.isBlank() || part.equals(".") || part.equals(".."))
        || RepositoryMapService.sensitive(relative))
      throw new IllegalArgumentException("Excluded or outside text path");
    Path cursor = root;
    for (var part : relative) {
      cursor = cursor.resolve(part);
      if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor))
          throw new IllegalArgumentException("Linked or aliased text path rejected");
      } else if (!cursor.equals(root.resolve(relative)))
        throw new IllegalArgumentException("Existing parent directory required");
    }
    if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(cursor, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException("Regular text target required");
    return cursor;
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public static String read(Path root, String name) throws IOException {
    Path path = path(root, name);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
    try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = stream.readNBytes(65537);
      if (bytes.length > 65536) throw new IllegalArgumentException("Text exceeds 64KiB");
      for (byte value : bytes)
        if (value == 0) throw new IllegalArgumentException("Binary text rejected");
      try {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      } catch (java.nio.charset.CharacterCodingException invalid) {
        throw new IllegalArgumentException("UTF-8 text required");
      }
    }
  }

  /** 共通の実行基盤で用いる要求内容の照合値を返す。 */
  public static Fingerprint fingerprint(Path root, String name) {
    try {
      Path file = path(root, name);
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Fingerprint(true, false, null);
      try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = stream.readNBytes(65537);
        if (bytes.length > 65536) return new Fingerprint(false, true, null);
        return new Fingerprint(
            true,
            true,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
    } catch (IOException | IllegalArgumentException unavailable) {
      return new Fingerprint(false, false, null);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  public static void text(String value) {
    if (value == null
        || value.indexOf(0) >= 0
        || value.getBytes(StandardCharsets.UTF_8).length > 65536)
      throw new IllegalArgumentException("Valid UTF-8 text of at most 64KiB required");
    try {
      StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value));
    } catch (java.nio.charset.CharacterCodingException invalid) {
      throw new IllegalArgumentException("Valid Unicode required");
    }
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  public static String hash(String text) {
    if (text == null) return null;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private static void validate(Path root, String id, List<Change> changes) throws IOException {
    if (id == null
        || !id.matches("[a-f0-9-]{36}")
        || changes == null
        || changes.isEmpty()
        || changes.size() > 64)
      throw new IllegalArgumentException("Bounded transaction ID and changes required");
    int total = 0;
    var names = new HashSet<String>();
    for (var change : changes) {
      path(root, change.path());
      if (!names.add(change.path().toLowerCase(Locale.ROOT))
          || change.before() == null && change.after() == null
          || Objects.equals(change.before(), change.after()))
        throw new IllegalArgumentException("Unique actual text changes required");
      for (String value : new String[] {change.before(), change.after()})
        if (value != null) {
          text(value);
          total += value.getBytes(StandardCharsets.UTF_8).length;
        }
    }
    if (total > 1048576) throw new IllegalArgumentException("Transaction text exceeds 1MiB");
  }
}
