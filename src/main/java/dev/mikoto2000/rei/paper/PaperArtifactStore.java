package dev.mikoto2000.rei.paper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 論文成果物の記録を保持し、後続の処理で参照できるようにする。 */
@Component
public class PaperArtifactStore {
  /** 作業ルート。 */
  private final Path root;

  /** この機能の動作設定。 */
  private final PaperProperties properties;

  /** 論文の調査と整理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public PaperArtifactStore(PaperProperties properties) {
    this(
        dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("papers"),
        properties);
  }

  /** 指定された値や利用先を使って、PaperArtifactStoreを初期化する。 */
  public PaperArtifactStore(Path root, PaperProperties properties) {
    this.root = root.toAbsolutePath().normalize();
    this.properties = properties;
  }

  /** 論文の調査と整理で用いるパスを返す。 */
  private Path path(String id, String kind, String extension) {
    try {
      if (!UUID.fromString(id).toString().equals(id)
          || !Set.of("originals", "extracted", "translations").contains(kind)
          || !extension.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException();
      Path p = root.resolve(kind).resolve(id + "." + extension);
      for (Path part = p; part != null; part = part.getParent())
        if (Files.isSymbolicLink(part)) throw new IllegalArgumentException();
      return p;
    } catch (RuntimeException e) {
      throw new PaperException(PaperException.Code.LIBRARY_STORAGE_FAILED, "安全でない Artifact パス", e);
    }
  }

  /** 指定された内容を書き込み先へ反映する。 */
  public void write(String id, String kind, String ext, byte[] bytes, PaperOperation op) {
    op.check();
    if (bytes.length
        > Math.max(properties.getMaxPdfBytes(), properties.getMaxExtractedChars() * 8L))
      throw new PaperException(PaperException.Code.LIBRARY_STORAGE_FAILED, "Artifact サイズ上限");
    Path target = path(id, kind, ext), tmp = null;
    try {
      Files.createDirectories(target.getParent());
      tmp = Files.createTempFile(target.getParent(), ".paper-", ".tmp");
      Files.write(tmp, bytes);
      op.check();
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new PaperException(PaperException.Code.LIBRARY_STORAGE_FAILED, "Artifact 保存失敗", e);
    } finally {
      if (tmp != null)
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
        }
    }
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  public Optional<byte[]> read(String id, String kind, String ext) {
    return readBounded(
        id,
        kind,
        ext,
        Math.max(properties.getMaxPdfBytes(), properties.getMaxExtractedChars() * 8L));
  }

  /** 必要な内容を参照できるよう、上限内に収めたを読み取る。 */
  public Optional<byte[]> readBounded(String id, String kind, String ext, long maximum) {
    Path target = path(id, kind, ext);
    try {
      if (!Files.exists(target)) return Optional.empty();
      if (maximum < 1
          || maximum > Integer.MAX_VALUE - 1
          || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
          || Files.size(target) > maximum) throw new IOException("size");
      try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = input.readNBytes((int) maximum + 1);
        if (bytes.length > maximum) throw new IOException("size");
        return Optional.of(bytes);
      }
    } catch (IOException e) {
      throw new PaperException(PaperException.Code.LIBRARY_READ_FAILED, "Artifact 読込失敗", e);
    }
  }

  /** 論文の調査と整理において、存在に該当するか判定する。 */
  public boolean exists(String id, String kind, String ext) {
    return Files.isRegularFile(path(id, kind, ext), LinkOption.NOFOLLOW_LINKS);
  }

  /** 指定された対象を削除する。 */
  public void delete(String id, String kind, String ext) {
    try {
      Files.deleteIfExists(path(id, kind, ext));
    } catch (IOException e) {
      throw new PaperException(PaperException.Code.LIBRARY_DELETE_FAILED, "Artifact 削除失敗", e);
    }
  }

  // Files are removed first; metadata remains retryable if any deletion or the DB commit fails.
  /** 論文の調査と整理に関する保持期限を過ぎた情報の削除を処理する。 */
  public void purge(String id, PaperOperation op) {
    path(id, "originals", "pdf");
    try {
      for (String kind : List.of("originals", "extracted", "translations")) {
        Path parent = path(id, kind, "pdf").getParent();
        if (!Files.exists(parent)) continue;
        try (var paths = Files.newDirectoryStream(parent, id + ".*")) {
          for (Path p : paths) {
            op.check();
            if (Files.isSymbolicLink(p)) throw new IOException("symlink");
            Files.delete(p);
          }
        }
      }
    } catch (IOException e) {
      throw new PaperException(
          PaperException.Code.LIBRARY_DELETE_FAILED, "Artifact 削除失敗。再実行できます", e);
    }
  }
}
