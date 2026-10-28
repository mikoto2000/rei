package dev.mikoto2000.rei.durable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;

/** Reads immutable Git tree/blob objects without checkout, archive exclusions, or shell commands. */
public final class GitSnapshotSource {
  private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

  record Blob(String path, String mode, String type, String object) {}

  record Page(List<DurableTaskStore.Captured> entries, boolean finished) {}

  private GitSnapshotSource() {}

  public static String head(Path root) throws IOException {
    String top = command(root, "rev-parse", "--show-toplevel");
    if (!Path.of(top).toRealPath().equals(root.toRealPath()))
      throw new IOException("Git Snapshot requires the repository root");
    String head = command(root, "rev-parse", "--verify", "HEAD^{commit}");
    if (!head.matches("[0-9a-f]{40}|[0-9a-f]{64}")) throw new IOException("Invalid commit ID");
    return head;
  }

  private static String command(Path root, String... arguments) throws IOException {
    var process = start(root, arguments);
    try {
      byte[] output = process.getInputStream().readNBytes(8193);
      if (output.length > 8192) throw new IOException("Git metadata output limit");
      finished(process);
      return new String(output, StandardCharsets.UTF_8).strip();
    } finally {
      process.destroy();
    }
  }

  static Page page(Path root, String commit, String after, int limit, List<String> exclusions)
      throws IOException {
    var candidates = new PriorityQueue<Blob>(Comparator.comparing(Blob::path).reversed());
    long remaining = 0;
    var listing = start(root, "ls-tree", "-r", "-z", "--full-tree", commit);
    try {
      String record;
      while ((record = terminated(listing.getInputStream(), 32768)) != null) {
        int tab = record.indexOf('\t');
        if (tab < 0) throw new IOException("Invalid Git tree output");
        String path = record.substring(tab + 1);
        if (path.compareTo(after) <= 0) continue;
        String[] fields = record.substring(0, tab).split(" ");
        if (fields.length != 3 || !fields[2].matches("[0-9a-f]{40}|[0-9a-f]{64}"))
          throw new IOException("Invalid Git object output");
        remaining++;
        candidates.add(new Blob(path, fields[0], fields[1], fields[2]));
        if (candidates.size() > limit) candidates.remove();
      }
      finished(listing);
    } finally {
      listing.destroy();
    }
    var ordered = new ArrayList<>(candidates);
    ordered.sort(Comparator.comparing(Blob::path));
    var entries = new ArrayList<DurableTaskStore.Captured>();
    var batch = start(root, "cat-file", "--batch", "-Z");
    long bytes = 0;
    try {
      for (var blob : ordered) {
        String reason = excluded(blob.path(), exclusions);
        if (blob.mode().equals("120000")) reason = "SYMBOLIC_LINK";
        else if (!blob.type().equals("blob")) reason = "SUBMODULE_NOT_CAPTURED";
        if (!reason.isEmpty()) {
          entries.add(SnapshotEnumerator.entry(blob.path(), null, null, reason, "", false));
          continue;
        }
        batch.getOutputStream().write((blob.object() + "\0").getBytes(StandardCharsets.US_ASCII));
        batch.getOutputStream().flush();
        String header = terminated(batch.getInputStream(), 256);
        String[] fields = header == null ? new String[0] : header.split(" ");
        if (fields.length != 3 || !fields[0].equals(blob.object()) || !fields[1].equals("blob"))
          throw new IOException("Git blob unavailable");
        long size;
        try {
          size = Long.parseLong(fields[2]);
        } catch (NumberFormatException invalid) {
          throw new IOException("Invalid Git blob size", invalid);
        }
        if (size < 0 || size > MAX_FILE_BYTES) {
          entries.add(SnapshotEnumerator.entry(blob.path(), null, null, "", "FILE_BYTE_LIMIT", false));
          return new Page(entries, false);
        }
        byte[] content = batch.getInputStream().readNBytes((int) size);
        if (content.length != size || batch.getInputStream().read() != 0)
          throw new IOException("Incomplete Git blob");
        entries.add(SnapshotEnumerator.capturedBytes(blob.path(), content));
        bytes += size;
        if (bytes >= 8L * 1024 * 1024) break;
      }
      batch.getOutputStream().close();
      finished(batch);
    } finally {
      batch.destroy();
    }
    return new Page(entries, remaining == entries.size());
  }

  private static String excluded(String path, List<String> exclusions) {
    for (var pattern : exclusions) {
      var matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
      // Test ancestors too: excluding a directory must exclude every committed descendant.
      Path candidate = Path.of(path);
      while (candidate != null) {
        if (matcher.matches(candidate) || matcher.matches(candidate.resolve("_"))) return "EXCLUDED:" + pattern;
        candidate = candidate.getParent();
      }
    }
    return "";
  }

  private static String terminated(InputStream input, int limit) throws IOException {
    var bytes = new ByteArrayOutputStream();
    for (int value; (value = input.read()) != -1;) {
      if (value == 0) return bytes.toString(StandardCharsets.UTF_8);
      if (bytes.size() >= limit) throw new IOException("Git output record limit");
      bytes.write(value);
    }
    if (bytes.size() != 0) throw new IOException("Incomplete Git output record");
    return null;
  }

  private static Process start(Path root, String... arguments) throws IOException {
    var command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    var builder = new ProcessBuilder(command).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
    builder.environment().put("GIT_NO_REPLACE_OBJECTS", "1");
    builder.environment().put("GIT_NO_LAZY_FETCH", "1");
    var process = builder.start();
    process.onExit().orTimeout(30, TimeUnit.SECONDS).exceptionally(error -> {
      process.destroyForcibly();
      return null;
    });
    return process;
  }

  private static void finished(Process process) throws IOException {
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
        throw new IOException("Git source unavailable");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException();
    }
  }
}
