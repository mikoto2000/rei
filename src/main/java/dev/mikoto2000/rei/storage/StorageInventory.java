package dev.mikoto2000.rei.storage;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * データ保存の共通処理に必要な保存保有情報を扱う。
 *
 * <p>Bounded observations only. No repository refresh, hashing, or filesystem mutations.
 */
public final class StorageInventory {
  /**
   * データ保存の共通処理で受け渡す分類を表す。
   *
   * @param files ファイル一覧
   * @param physicalBytes physicalバイト数
   * @param records 記録一覧
   */
  public record Category(long files, long physicalBytes, Long records) {}

  /**
   * データ保存の共通処理で受け渡すレポートを表す。
   *
   * @param root 作業ルート
   * @param measuredAt 計測した日時
   * @param categories 分類一覧
   * @param unknown 不明
   * @param deltaBytes 差分バイト数
   * @param bytesPerSecond バイト数ごとの秒
   * @param complete 完了
   */
  public record Report(
      Path root,
      Instant measuredAt,
      Map<String, Category> categories,
      List<String> unknown,
      Long deltaBytes,
      Double bytesPerSecond,
      boolean complete) {}

  /**
   * データ保存の共通処理で受け渡す前回を表す。
   *
   * @param at 日時
   * @param bytes バイト数
   */
  private record Previous(Instant at, long bytes) {}

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 項目上限。 */
  private final int entryLimit;

  /** 内容上限。 */
  private final long contentLimit;

  /** 前回。 */
  private final Map<Path, Previous> previous = new LinkedHashMap<>();

  /** 指定された値や利用先を使って、StorageInventoryを初期化する。 */
  public StorageInventory(Clock clock, int entryLimit, long contentLimit) {
    if (entryLimit < 1 || contentLimit < 1)
      throw new IllegalArgumentException("Positive scan budgets required");
    this.clock = Objects.requireNonNull(clock);
    this.entryLimit = entryLimit;
    this.contentLimit = contentLimit;
  }

  /** データ保存の共通処理で用いる計測を返す。 */
  public synchronized Report measure(Path supplied) {
    Path root = supplied.toAbsolutePath().normalize();
    var rows = new TreeMap<String, Category>();
    var unknown = new ArrayList<String>();
    unknown.add(
        "Reference protection and retention candidates: unverified; inventory does not authorize deletion");
    unknown.add(
        "Physical bytes are file lengths, not allocated disk blocks; SQLite logical rows/free pages are unscanned");
    long[] budget = {contentLimit};
    int[] entries = {0};
    boolean[] complete = {true};
    if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption.class),
            32,
            new SimpleFileVisitor<>() {
              /** データ保存の共通処理において、admitに該当するか判定する。 */
              private boolean admit() {
                if (++entries[0] <= entryLimit) return true;
                complete[0] = false;
                unknown.add("Entry budget exhausted; remaining storage unscanned");
                return false;
              }

              /** データ保存の共通処理で用いるpre対象の確認ディレクトリを返す。 */
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return admit() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
              }

              /** データ保存の共通処理で用いる対象の確認ファイルを返す。 */
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!admit()) return FileVisitResult.TERMINATE;
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!attrs.isRegularFile()) {
                  complete[0] = false;
                  unknown.add("Unscanned link, special file, or depth limit: " + relative);
                  return FileVisitResult.CONTINUE;
                }
                String category = classify(relative);
                Long count = null;
                if (!category.equals("migration-backups")
                    && (file.toString().endsWith(".jsonl")
                        || category.equals("sessions")
                        || category.equals("turns"))) {
                  if (attrs.size() > budget[0]) unknown.add("Content budget: " + relative);
                  else {
                    budget[0] -= attrs.size();
                    try {
                      count = records(file, attrs.size(), file.toString().endsWith(".jsonl"));
                    } catch (IOException error) {
                      unknown.add(
                          "Malformed, incomplete, changed, or unreadable records: " + relative);
                    }
                  }
                }
                var old = rows.get(category);
                rows.put(
                    category,
                    new Category(
                        (old == null ? 0 : old.files()) + 1,
                        (old == null ? 0 : old.physicalBytes()) + attrs.size(),
                        old == null
                            ? count
                            : old.records() == null || count == null
                                ? null
                                : old.records() + count));
                if (category.equals("unknown")) unknown.add("Unclassified file: " + relative);
                return FileVisitResult.CONTINUE;
              }

              /** データ保存の共通処理で用いる対象の確認ファイル失敗を返す。 */
              @Override
              public FileVisitResult visitFileFailed(Path file, IOException error) {
                complete[0] = false;
                unknown.add("Unreadable path: " + root.relativize(file));
                return admit() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
              }
            });
      } catch (IOException error) {
        complete[0] = false;
        unknown.add("Storage scan failed: " + error.getClass().getSimpleName());
      }
    }
    long total = rows.values().stream().mapToLong(Category::physicalBytes).sum();
    Instant now = clock.instant();
    var before = previous.get(root);
    Long delta = complete[0] && before != null ? total - before.bytes() : null;
    double elapsed =
        before == null ? 0 : Duration.between(before.at(), now).toNanos() / 1_000_000_000.0;
    Double rate = delta != null && elapsed > 0 ? delta / elapsed : null;
    if (complete[0]) {
      previous.put(root, new Previous(now, total));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (previous.size() > 64) previous.remove(previous.keySet().iterator().next());
    } else previous.remove(root);
    return new Report(root, now, Map.copyOf(rows), List.copyOf(unknown), delta, rate, complete[0]);
  }

  /** 得られた情報を分類し、後続の判断で扱い分けられるようにする。 */
  private static String classify(String path) {
    if (path.startsWith(".storage/backups/")) return "migration-backups";
    if (path.endsWith("sessions.json")) return "sessions";
    if (path.contains("state/turns/")) return "turns";
    if (path.endsWith("events/events.jsonl")) return "events";
    if (path.endsWith("logs/activity.jsonl")) return "activity";
    if (path.contains("state/context/results/")) return "raw-results";
    if (path.contains("conversations/") || path.contains("conversation-logs/"))
      return "conversations";
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

  /** データ保存の共通処理で用いる記録一覧を返す。 */
  private static long records(Path file, long observed, boolean jsonl) throws IOException {
    if (jsonl && observed > 0)
      try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
        channel.position(observed - 1);
        var last = java.nio.ByteBuffer.allocate(1);
        if (channel.read(last) != 1 || last.array()[0] != '\n')
          throw new IOException("Incomplete final JSONL line");
      }
    // Limit input even if a concurrent writer grows the file after stat.
    try (InputStream source = Files.newInputStream(file);
        var input =
            new FilterInputStream(source) {
              /** 残量。 */
              long remaining = observed;

              /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
              @Override
              public int read() throws IOException {
                if (remaining == 0) return -1;
                int v = super.read();
                if (v >= 0) remaining--;
                return v;
              }

              /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
              @Override
              public int read(byte[] bytes, int off, int len) throws IOException {
                if (remaining == 0) return -1;
                int n = super.read(bytes, off, (int) Math.min(len, remaining));
                if (n > 0) remaining -= n;
                return n;
              }
            };
        JsonParser parser = new JsonFactory().createParser(input)) {
      long count = 0;
      if (jsonl) {
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if (token != JsonToken.START_OBJECT) throw new IOException("Expected JSONL object");
          parser.skipChildren();
          count++;
        }
      } else {
        if (parser.nextToken() != JsonToken.START_ARRAY) throw new IOException("Expected array");
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          if (parser.currentToken() != JsonToken.START_OBJECT)
            throw new IOException("Expected row");
          parser.skipChildren();
          count++;
        }
        if (parser.nextToken() != null) throw new IOException("Trailing data");
      }
      if (Files.size(file) != observed) throw new IOException("Changed during scan");
      return count;
    }
  }
}
