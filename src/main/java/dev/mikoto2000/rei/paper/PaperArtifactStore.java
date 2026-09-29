package dev.mikoto2000.rei.paper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PaperArtifactStore {
  private final Path root;
  private final PaperProperties properties;

  @org.springframework.beans.factory.annotation.Autowired
  public PaperArtifactStore(PaperProperties properties) {
    this(
        dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("papers"),
        properties);
  }

  public PaperArtifactStore(Path root, PaperProperties properties) {
    this.root = root.toAbsolutePath().normalize();
    this.properties = properties;
  }

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

  public Optional<byte[]> read(String id, String kind, String ext) {
    Path target = path(id, kind, ext);
    try {
      if (!Files.exists(target)) return Optional.empty();
      if (Files.size(target)
          > Math.max(properties.getMaxPdfBytes(), properties.getMaxExtractedChars() * 8L))
        throw new IOException("size");
      return Optional.of(Files.readAllBytes(target));
    } catch (IOException e) {
      throw new PaperException(PaperException.Code.LIBRARY_READ_FAILED, "Artifact 読込失敗", e);
    }
  }

  public boolean exists(String id, String kind, String ext) {
    return Files.isRegularFile(path(id, kind, ext), LinkOption.NOFOLLOW_LINKS);
  }

  public void delete(String id, String kind, String ext) {
    try {
      Files.deleteIfExists(path(id, kind, ext));
    } catch (IOException e) {
      throw new PaperException(PaperException.Code.LIBRARY_DELETE_FAILED, "Artifact 削除失敗", e);
    }
  }

  // Files are removed first; metadata remains retryable if any deletion or the DB commit fails.
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
