package dev.mikoto2000.rei.storage;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.core.*;

/** Bounded observations only. No repository refresh, hashing, or filesystem mutations. */
public final class StorageInventory {
  public record Category(long files, long physicalBytes, Long records) {}
  public record Report(Path root, Instant measuredAt, Map<String, Category> categories,
      List<String> unknown, Long deltaBytes, Double bytesPerSecond, boolean complete) {}
  private record Previous(Instant at, long bytes) {}
  private final Clock clock;
  private final int entryLimit;
  private final long contentLimit;
  private final Map<Path, Previous> previous = new LinkedHashMap<>();
  public StorageInventory(Clock clock, int entryLimit, long contentLimit) {
    if (entryLimit < 1 || contentLimit < 1) throw new IllegalArgumentException("Positive scan budgets required");
    this.clock = Objects.requireNonNull(clock); this.entryLimit = entryLimit; this.contentLimit = contentLimit;
  }
  public synchronized Report measure(Path supplied) {
    Path root = supplied.toAbsolutePath().normalize();
    var rows = new TreeMap<String, Category>();
    var unknown = new ArrayList<String>();
    unknown.add("Reference protection and retention candidates: unverified; inventory does not authorize deletion");
    unknown.add("Physical bytes are file lengths, not allocated disk blocks; SQLite logical rows/free pages are unscanned");
    long[] budget = {contentLimit}; int[] entries = {0}; boolean[] complete = {true};
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 32, new SimpleFileVisitor<>() {
          private boolean admit() {
            if (++entries[0] <= entryLimit) return true;
            complete[0] = false; unknown.add("Entry budget exhausted; remaining storage unscanned"); return false;
          }
          @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return admit() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
          }
          @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!admit()) return FileVisitResult.TERMINATE;
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (!attrs.isRegularFile()) {
              complete[0] = false; unknown.add("Unscanned link, special file, or depth limit: " + relative);
              return FileVisitResult.CONTINUE;
            }
            String category = classify(relative);
            Long count = null;
            if (file.toString().endsWith(".jsonl") || category.equals("sessions") || category.equals("turns")) {
              if (attrs.size() > budget[0]) unknown.add("Content budget: " + relative);
              else {
                budget[0] -= attrs.size();
                try { count = records(file, attrs.size(), file.toString().endsWith(".jsonl")); }
                catch (IOException error) { unknown.add("Malformed, incomplete, changed, or unreadable records: " + relative); }
              }
            }
            var old = rows.get(category);
            rows.put(category, new Category((old == null ? 0 : old.files()) + 1,
                (old == null ? 0 : old.physicalBytes()) + attrs.size(),
                old == null ? count : old.records() == null || count == null ? null : old.records() + count));
            if (category.equals("unknown")) unknown.add("Unclassified file: " + relative);
            return FileVisitResult.CONTINUE;
          }
          @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
            complete[0] = false; unknown.add("Unreadable path: " + root.relativize(file));
            return admit() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
          }
        });
      } catch (IOException error) { complete[0] = false; unknown.add("Storage scan failed: " + error.getClass().getSimpleName()); }
    }
    long total = rows.values().stream().mapToLong(Category::physicalBytes).sum();
    Instant now = clock.instant(); var before = previous.get(root);
    Long delta = complete[0] && before != null ? total - before.bytes() : null;
    double elapsed = before == null ? 0 : Duration.between(before.at(), now).toNanos() / 1_000_000_000.0;
    Double rate = delta != null && elapsed > 0 ? delta / elapsed : null;
    if (complete[0]) {
      previous.put(root, new Previous(now, total));
      if (previous.size() > 64) previous.remove(previous.keySet().iterator().next());
    } else previous.remove(root);
    return new Report(root, now, Map.copyOf(rows), List.copyOf(unknown), delta, rate, complete[0]);
  }
  private static String classify(String path) {
    if (path.endsWith("sessions.json")) return "sessions";
    if (path.contains("state/turns/")) return "turns";
    if (path.endsWith("events/events.jsonl")) return "events";
    if (path.endsWith("logs/activity.jsonl")) return "activity";
    if (path.contains("state/context/results/")) return "raw-results";
    if (path.contains("conversations/") || path.contains("conversation-logs/")) return "conversations";
    if (path.endsWith("-wal")) return "sqlite-wal";
    if (path.endsWith("-shm")) return "sqlite-shm";
    if (path.endsWith(".db")) return "sqlite";
    if (path.startsWith("artifacts/")) return "artifacts";
    if (path.contains("voice/") || path.contains("models/")) return "voice-models";
    if (path.contains("images/")) return "images";
    if (path.contains("papers/")) return "papers";
    if (path.contains("worktrees/")) return "worktrees";
    if (path.contains("logs/")) return "logs";
    return "unknown";
  }
  private static long records(Path file, long observed, boolean jsonl) throws IOException {
    if (jsonl && observed > 0) try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
      channel.position(observed - 1);
      var last = java.nio.ByteBuffer.allocate(1);
      if (channel.read(last) != 1 || last.array()[0] != '\n') throw new IOException("Incomplete final JSONL line");
    }
    // Limit input even if a concurrent writer grows the file after stat.
    try (InputStream source = Files.newInputStream(file); var input = new FilterInputStream(source) {
      long remaining = observed;
      @Override public int read() throws IOException { if (remaining == 0) return -1; int v = super.read(); if (v >= 0) remaining--; return v; }
      @Override public int read(byte[] bytes, int off, int len) throws IOException {
        if (remaining == 0) return -1; int n = super.read(bytes, off, (int)Math.min(len, remaining)); if (n > 0) remaining -= n; return n;
      }
    }; JsonParser parser = new JsonFactory().createParser(input)) {
      long count = 0;
      if (jsonl) {
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if (token != JsonToken.START_OBJECT) throw new IOException("Expected JSONL object");
          parser.skipChildren(); count++;
        }
      } else {
        if (parser.nextToken() != JsonToken.START_ARRAY) throw new IOException("Expected array");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          if (parser.currentToken() != JsonToken.START_OBJECT) throw new IOException("Expected row");
          parser.skipChildren(); count++;
        }
        if (parser.nextToken() != null) throw new IOException("Trailing data");
      }
      if (Files.size(file) != observed) throw new IOException("Changed during scan");
      return count;
    }
  }
}
