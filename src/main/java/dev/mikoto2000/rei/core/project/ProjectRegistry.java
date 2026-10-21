package dev.mikoto2000.rei.core.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * プロジェクトを登録して管理し、識別子から対象を参照できるようにする。
 *
 * <p>Small, atomic JSON registry. Failed writes leave both the file and published state unchanged.
 */
public final class ProjectRegistry {
  // Shell and HTTP use separate registry instances in the same Backend. A bounded
  // lock stripe prevents overlapping read/modify/replace transactions for a file.
  /** ファイル排他制御一覧。 */
  private static final Object[] FILE_LOCKS =
      java.util.stream.IntStream.range(0, 64).mapToObj(ignored -> new Object()).toArray();

  /** ファイル。 */
  private final Path file;

  /** ファイル排他制御。 */
  private final Object fileLock;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper();

  /** 完了条件状態の記録。 */
  private volatile List<ProjectContext> completionSnapshot = List.of();

  /** 指定された値や利用先を使って、ProjectRegistryを初期化する。 */
  public ProjectRegistry(Path file) {
    this.file = file.toAbsolutePath().normalize();
    this.fileLock = FILE_LOCKS[Math.floorMod(this.file.hashCode(), FILE_LOCKS.length)];
    synchronized (fileLock) {
      try {
        read();
      } catch (RuntimeException ignored) {
        /* Optional startup completion snapshot. */
      }
    }
  }

  /** プロジェクトごとの作業環境で用いる完了条件状態の記録を返す。 */
  public List<ProjectContext> completionSnapshot() {
    return completionSnapshot;
  }

  /**
   * プロジェクトごとの作業環境で受け渡す項目を表す。
   *
   * @param id 識別子
   * @param name 名前
   * @param path パス
   */
  public record Entry(String id, String name, String path) {
    /** プロジェクトごとの作業環境で用いる文脈を返す。 */
    ProjectContext context() {
      return new ProjectContext(id, name, Path.of(path));
    }
  }

  /**
   * プロジェクトごとの作業環境で受け渡す文書を表す。
   *
   * @param projects プロジェクト一覧
   */
  public record Document(List<Entry> projects) {}

  /** 指定や設定を照合し、実際に利用する対象を確定する。 */
  public synchronized ProjectContext resolve(Path path) {
    synchronized (fileLock) {
      Path canonical = canonical(path);
      var entries = read();
      for (var entry : entries) if (Path.of(entry.path()).equals(canonical)) return entry.context();
      var entry =
          new Entry(
              UUID.randomUUID().toString(),
              canonical.getFileName() == null
                  ? canonical.toString()
                  : canonical.getFileName().toString(),
              canonical.toString());
      entries.add(entry);
      save(entries);
      return entry.context();
    }
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public synchronized List<ProjectContext> list() {
    synchronized (fileLock) {
      return read().stream().map(Entry::context).toList();
    }
  }

  /**
   * 指定や設定に応じた対象を利用するため、別識別子を確定する。
   *
   * <p>Web callers may resolve registered identities only; never interpret input as a path.
   */
  public synchronized Optional<ProjectContext> resolveById(String id) {
    synchronized (fileLock) {
      return read().stream().filter(entry -> entry.id().equals(id)).findFirst().map(Entry::context);
    }
  }

  /** プロジェクトごとの作業環境に関するrelocateを処理する。 */
  public synchronized void relocate(String id, Path path) {
    synchronized (fileLock) {
      Path canonical = canonical(path);
      var entries = read();
      if (entries.stream()
          .anyMatch(e -> !e.id().equals(id) && e.path().equals(canonical.toString())))
        throw new IllegalArgumentException("Project location already registered");
      boolean found = false;
      for (int i = 0; i < entries.size(); i++)
        if (entries.get(i).id().equals(id)) {
          entries.set(i, new Entry(id, entries.get(i).name(), canonical.toString()));
          found = true;
        }
      if (!found) throw new IllegalArgumentException("Unknown project: " + id);
      save(entries);
    }
  }

  /** 指定された対象を管理から取り除く。 */
  public synchronized void remove(Path path) {
    synchronized (fileLock) {
      var entries = read();
      entries.removeIf(e -> Path.of(e.path()).equals(path.toAbsolutePath().normalize()));
      save(entries);
    }
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private List<Entry> read() {
    if (!Files.exists(file)) {
      completionSnapshot = List.of();
      return new ArrayList<>();
    }
    try {
      var entries =
          new ArrayList<>(mapper.readValue(Files.readString(file), Document.class).projects());
      completionSnapshot = entries.stream().map(Entry::context).toList();
      return entries;
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read project registry: " + file, e);
    }
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  private void save(List<Entry> entries) {
    Path temporary = null;
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "projects-", ".tmp");
      Files.writeString(
          temporary,
          mapper.writerWithDefaultPrettyPrinter().writeValueAsString(new Document(entries)));
      try {
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
      }
      completionSnapshot = entries.stream().map(Entry::context).toList();
    } catch (IOException e) {
      throw new IllegalStateException("Cannot save project registry: " + file, e);
    } finally {
      if (temporary != null)
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException e) {
          org.slf4j.LoggerFactory.getLogger(ProjectRegistry.class)
              .warn("Cannot remove registry temporary file", e);
        }
    }
  }

  /** プロジェクトごとの作業環境で用いる正規を返す。 */
  static Path canonical(Path path) {
    try {
      Path canonical = path.toRealPath();
      if (!Files.isDirectory(canonical))
        throw new IllegalArgumentException("Project path is not a directory: " + path);
      return canonical;
    } catch (NoSuchFileException e) {
      throw new IllegalArgumentException("Project directory does not exist: " + path, e);
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot resolve project: " + path, e);
    }
  }
}
