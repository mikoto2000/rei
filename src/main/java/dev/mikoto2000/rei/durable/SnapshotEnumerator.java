package dev.mikoto2000.rei.durable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded pages with a persisted directory frontier; no depth cutoff or silent list truncation. */
public final class SnapshotEnumerator {
  @FunctionalInterface
  interface FileReader {
    byte[] read(Path path) throws IOException;
  }

  private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
  private final DurableTaskStore store;
  private final FileReader reader;

  public SnapshotEnumerator(DurableTaskStore store) {
    this(store, SnapshotEnumerator::readBytes);
  }

  SnapshotEnumerator(DurableTaskStore store, FileReader reader) {
    this.store = store;
    this.reader = reader;
  }

  public DurableTaskStore.Task step(DurableTaskStore.Scope scope, String task, int limit) {
    DurableTaskStore.pageLimit(limit);
    if (store.status(scope, task).enumeration().equals("VALIDATING")) {
      validateDirectories(scope, task);
      return store.status(scope, task);
    }
    var directory = store.nextDirectory(scope, task);
    if (directory == null) return store.status(scope, task);
    Path root = Path.of(scope.root());
    var captured = new ArrayList<DurableTaskStore.Captured>();
    try {
      var source = store.source(scope, task);
      if (source.mode().equals("COMMIT")) {
        var page = GitSnapshotSource.page(root, source.reference(), directory.cursor(), limit, store.exclusions(scope, task));
        store.captured(scope, task, directory, page.entries(), page.finished(), "");
        return store.status(scope, task);
      }
      Path current = root.resolve(directory.path());
      if (Files.isSymbolicLink(current) || !current.toRealPath().startsWith(root.toRealPath()))
        throw new IOException("Directory changed");
      String stamp = stamp(current);
      if (!directory.stamp().isEmpty() && !directory.stamp().equals(stamp))
        throw new DirectoryChanged();
      var names = new PriorityQueue<String>(Comparator.reverseOrder());
      long remaining = 0;
      try (var children = Files.newDirectoryStream(current)) {
        for (var child : children) {
          if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
          var fileName = child.getFileName();
          if (fileName == null) throw new IOException("Invalid directory entry");
          String name = fileName.toString();
          if (name.compareTo(directory.cursor()) <= 0) continue;
          remaining++;
          names.add(name);
          if (names.size() > limit) names.remove();
        }
      }
      var ordered = new ArrayList<>(names);
      ordered.sort(Comparator.naturalOrder());
      var exclusions = store.exclusions(scope, task);
      long batchBytes = 0;
      for (var name : ordered) {
        String relative = directory.path().isEmpty() ? name : directory.path() + "/" + name;
        DurableTaskStore.Captured entry;
        try {
          entry = capture(root, relative, exclusions);
        } catch (IOException denied) {
          entry = entry(relative, null, null, "", denied instanceof java.nio.file.AccessDeniedException ? "ACCESS_DENIED" : "SOURCE_UNAVAILABLE", false);
        }
        captured.add(entry);
        batchBytes += entry.size();
        if (batchBytes >= 8L * 1024 * 1024) break;
      }
      if (!stamp.equals(stamp(current))) throw new DirectoryChanged();
      store.captured(scope, task, directory, captured, remaining == captured.size(), stamp);
      if (store.status(scope, task).enumeration().equals("VALIDATING")) validateDirectories(scope, task);
    } catch (IOException error) {
      store.scanFailed(scope, task, directory.path(), error instanceof DirectoryChanged ? "DIRECTORY_CHANGED" : error instanceof java.nio.file.AccessDeniedException ? "ACCESS_DENIED" : "SOURCE_UNAVAILABLE");
    }
    return store.status(scope, task);
  }

  private static final class DirectoryChanged extends IOException {}

  private static String stamp(Path directory) throws IOException {
    var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw new DirectoryChanged();
    return attributes.lastModifiedTime() + "|" + attributes.fileKey();
  }

  private void validateDirectories(DurableTaskStore.Scope scope, String task) {
    String cursor = null;
    try {
      do {
        var page = store.directories(scope, task, cursor);
        for (var directory : page.entries())
          if (!directory.stamp().equals(stamp(Path.of(scope.root()).resolve(directory.path()))))
            throw new DirectoryChanged();
        cursor = page.nextCursor();
      } while (cursor != null);
      store.seal(scope, task);
    } catch (IOException error) {
      store.scanFailed(scope, task, "", error instanceof DirectoryChanged ? "DIRECTORY_CHANGED" : "SOURCE_UNAVAILABLE");
    }
  }

  private DurableTaskStore.Captured capture(Path root, String relative, List<String> exclusions)
      throws IOException {
    Path path = root.resolve(relative);
    String reason = "";
    var fileName = path.getFileName();
    if (fileName == null) throw new IOException("Invalid snapshot path");
    if (fileName.toString().equalsIgnoreCase(".git")) reason = "GIT_METADATA";
    if (Files.isSymbolicLink(path)) reason = "SYMBOLIC_LINK";
    for (var pattern : exclusions) {
      var matcher = path.getFileSystem().getPathMatcher("glob:" + pattern);
      if (matcher.matches(Path.of(relative)) || matcher.matches(Path.of(relative + "/_"))) {
        reason = "EXCLUDED:" + pattern;
        break;
      }
    }
    if (!reason.isEmpty()) return entry(relative, null, null, reason, "", false);
    var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (before.isDirectory()) return entry(relative, null, null, "", "", true);
    if (!before.isRegularFile()) return entry(relative, null, null, "UNSUPPORTED_FILE_TYPE", "", false);
    if (before.size() > MAX_FILE_BYTES) return entry(relative, null, null, "", "FILE_BYTE_LIMIT", false);
    byte[] bytes = reader.read(path);
    var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (bytes.length > MAX_FILE_BYTES || before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
      return entry(relative, null, null, "", "SOURCE_CHANGED_DURING_CAPTURE", false);
    return capturedBytes(relative, bytes);
  }

  static DurableTaskStore.Captured capturedBytes(String relative, byte[] bytes) {
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException invalid) {
      return entry(relative, null, null, "UNSUPPORTED_ENCODING", "", false);
    }
    if (text.indexOf('\0') >= 0) return entry(relative, null, null, "BINARY_FILE", "", false);
    return entry(relative, bytes, text, "", "", false);
  }

  private static byte[] readBytes(Path path) throws IOException {
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      return input.readNBytes(MAX_FILE_BYTES + 1);
    }
  }

  static DurableTaskStore.Captured entry(String path, byte[] bytes, String text,
      String reason, String error, boolean directory) {
    String version = "";
    if (bytes != null) {
      try {
        version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    return new DurableTaskStore.Captured(path, bytes, text, version, reason, error, directory);
  }
}
