package dev.mikoto2000.rei.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 会話ログの記録を保持し、後続の処理で参照できるようにする。 */
@Component
public class ConversationLogStore {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(ConversationLogStore.class);

  /** ファイル日付。 */
  private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  /** ディレクトリ。 */
  private final Path directory;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper objectMapper;

  /** 書き込み排他制御。 */
  private final Object writeLock = new Object();

  /** 文脈発生順序一覧。 */
  private final java.util.Map<String, Long> contextSequences = new java.util.HashMap<>();

  private static final int NOTIFICATION_INDEX_FILES = 1024;
  private static final int NOTIFICATION_INDEX_CHARACTERS = 1024 * 1024;
  private final java.util.LinkedHashMap<Path, NotificationFileIndex> notificationIndexes =
      new java.util.LinkedHashMap<>(16, 0.75f, true);
  private int notificationIndexCharacters;

  private record NotificationFileStamp(long bytes, String modified, String created, String key) {}

  private record NotificationFileIndex(
      NotificationFileStamp stamp,
      java.util.Map<String, ConversationLogEntry> entries,
      boolean complete,
      int characters) {
    private NotificationFileIndex {
      entries = java.util.Map.copyOf(entries);
    }
  }

  /** 会話履歴と受付記録の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationLogStore() {
    this(null, Clock.systemDefaultZone(), new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  /** 指定された値や利用先を使って、ConversationLogStoreを初期化する。 */
  public ConversationLogStore(Path directory, Clock clock, ObjectMapper objectMapper) {
    this.directory = directory;
    this.clock = clock;
    this.objectMapper = objectMapper;
  }

  /** 既存の記録へ経緯を残すため、会話ログ保存先を追記する。 */
  public void append(String conversationId, String speaker, String content) {
    if (conversationId == null
        || conversationId.isBlank()
        || content == null
        || content.isBlank()) {
      return;
    }
    OffsetDateTime timestamp = OffsetDateTime.now(clock);
    Path directory = directoryFor(conversationId);
    Path file = directory.resolve(FILE_DATE.format(timestamp) + ".jsonl");
    synchronized (writeLock) {
      try {
        String id = conversationId.strip();
        long nextSequence =
            contextSequences.computeIfAbsent(
                    id,
                    key ->
                        readConversation(key).stream()
                            .mapToLong(ConversationLogEntry::sequence)
                            .max()
                            .orElse(0))
                + 1;
        ConversationLogEntry entry =
            new ConversationLogEntry(
                id, scopeOf(id), normalizeSpeaker(speaker), timestamp, content, nextSequence);
        Files.createDirectories(directory);
        Files.writeString(
            file,
            objectMapper.writeValueAsString(entry) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        contextSequences.put(id, nextSequence);
      } catch (IOException e) {
        log.warn("Failed to append conversation log: {}", file, e);
      }
    }
  }

  /** 作業習慣通知。 */
  public static final String BEHAVIOR_NOTIFICATION = "BEHAVIOR_NOTIFICATION";

  /**
   * 対象を特定できるよう、通知を探す。
   *
   * <p>The durable log is the idempotency source, including across restart/project switches.
   */
  public java.util.Optional<ConversationLogEntry> findNotification(String sourceId) {
    synchronized (writeLock) {
      var directories = new ArrayList<Path>();
      if (directory != null) directories.add(directory);
      else {
        var base = dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current();
        directories.add(base.resolve("conversations"));
        var projects = base.resolve("projects");
        if (Files.isDirectory(projects))
          try (var paths = Files.list(projects)) {
            paths
                .filter(Files::isDirectory)
                .forEach(p -> directories.add(p.resolve("conversations")));
          } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect notification history", e);
          }
      }
      for (var location : directories) {
        if (!Files.isDirectory(location)) continue;
        // Fail closed on unreadable history: never mistake an I/O failure for an absent id.
        try (var files = Files.list(location)) {
          for (var file :
              files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
            var found = notificationInFile(file, sourceId);
            if (found.isPresent()) return found;
          }
        } catch (IOException e) {
          throw new IllegalStateException("Cannot inspect notification history", e);
        }
      }
      return java.util.Optional.empty();
    }
  }

  /** Derived index only; the append-only JSONL remains the identity source across restarts. */
  private java.util.Optional<ConversationLogEntry> notificationInFile(Path file, String sourceId)
      throws IOException {
    var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
    var stamp =
        new NotificationFileStamp(
            attributes.size(),
            attributes.lastModifiedTime().toString(),
            attributes.creationTime().toString(),
            java.util.Objects.toString(attributes.fileKey(), null));
    var cached = notificationIndexes.get(file);
    if (sourceId != null && cached != null && cached.stamp().equals(stamp)) {
      var found = cached.entries().get(sourceId);
      if (found != null || cached.complete()) {
        // A cache hit must still fail closed when history is no longer readable.
        try (var readable = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
          return java.util.Optional.ofNullable(found);
        }
      }
    }
    var entries = new java.util.LinkedHashMap<String, ConversationLogEntry>();
    boolean complete = true;
    boolean retain = true;
    int characters = 0;
    ConversationLogEntry found = null;
    try (var lines = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = lines.readLine()) != null) {
        if (line.isBlank()) continue;
        var entry = objectMapper.readValue(line, ConversationLogEntry.class);
        if (!BEHAVIOR_NOTIFICATION.equals(entry.source())) continue;
        if (retain && entry.sourceId() != null && !entries.containsKey(entry.sourceId())) {
          if (entries.size() >= 1024
              || line.length() > NOTIFICATION_INDEX_CHARACTERS - characters) {
            retain = false;
            entries.clear();
            characters = 0;
          } else {
            entries.put(entry.sourceId(), entry);
            characters += line.length();
          }
        }
        if (sourceId.equals(entry.sourceId())) {
          found = entry;
          // Preserve the early-match contract even when a later line is malformed.
          complete = false;
          break;
        }
      }
    }
    cacheNotificationFile(
        file, retain ? new NotificationFileIndex(stamp, entries, complete, characters) : null);
    return java.util.Optional.ofNullable(found);
  }

  /** All access is serialized by writeLock, including the existing duplicate-check/append pair. */
  private void cacheNotificationFile(Path file, NotificationFileIndex index) {
    var previous = notificationIndexes.remove(file);
    if (previous != null) notificationIndexCharacters -= previous.characters();
    if (index == null) return;
    while (notificationIndexes.size() >= NOTIFICATION_INDEX_FILES
        || notificationIndexCharacters + index.characters() > NOTIFICATION_INDEX_CHARACTERS) {
      var oldest = notificationIndexes.entrySet().iterator();
      notificationIndexCharacters -= oldest.next().getValue().characters();
      oldest.remove();
    }
    notificationIndexes.put(file, index);
    notificationIndexCharacters += index.characters();
  }

  /** 既存の記録へ経緯を残すため、通知を追記する。 */
  public ConversationLogEntry appendNotification(
      String conversationId,
      String sourceId,
      String text,
      java.time.Instant occurredAt,
      java.util.Map<String, String> metadata) {
    if (conversationId == null
        || conversationId.isBlank()
        || sourceId == null
        || sourceId.isBlank()
        || text == null
        || text.isBlank())
      throw new IllegalArgumentException("Notification identity and text are required");
    synchronized (writeLock) {
      var existing = findNotification(sourceId);
      if (existing.isPresent()) return existing.get();
      var timestamp = occurredAt.atZone(clock.getZone()).toOffsetDateTime();
      var location = directoryFor(conversationId);
      var file = location.resolve(FILE_DATE.format(timestamp) + ".jsonl");
      long next =
          contextSequences.computeIfAbsent(
                  conversationId,
                  key ->
                      readConversation(key).stream()
                          .mapToLong(ConversationLogEntry::sequence)
                          .max()
                          .orElse(0))
              + 1;
      var entry =
          new ConversationLogEntry(
              conversationId,
              scopeOf(conversationId),
              "assistant",
              timestamp,
              text,
              next,
              BEHAVIOR_NOTIFICATION,
              sourceId,
              metadata);
      try {
        Files.createDirectories(location);
        Files.writeString(
            file,
            objectMapper.writeValueAsString(entry) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        contextSequences.put(conversationId, next);
        return entry;
      } catch (IOException e) {
        throw new IllegalStateException("Cannot append notification history", e);
      }
    }
  }

  /** 必要な内容を参照できるよう、全対象を読み取る。 */
  public List<ConversationLogEntry> readAll() {
    return readDirectory(directoryFor(null));
  }

  /**
   * 必要な内容を参照できるよう、会話を読み取る。
   *
   * <p>Full append-order conversation source, including interventions and auxiliary command
   * results.
   */
  public List<ConversationLogEntry> readConversation(String conversationId) {
    synchronized (writeLock) {
      var entries =
          readDirectory(directoryFor(conversationId)).stream()
              .filter(entry -> conversationId.equals(entry.conversationId()))
              .toList();
      var result = new ArrayList<ConversationLogEntry>();
      long legacySequence = 0;
      // Legacy records retain their established order and files. New appends always have explicit
      // cursors.
      for (var entry : entries)
        if (entry.sequence() == 0)
          result.add(
              new ConversationLogEntry(
                  entry.conversationId(),
                  entry.scope(),
                  entry.speaker(),
                  entry.timestamp(),
                  entry.content(),
                  ++legacySequence));
      entries.stream().filter(e -> e.sequence() > 0).forEach(result::add);
      result.sort(Comparator.comparingLong(ConversationLogEntry::sequence));
      return List.copyOf(result);
    }
  }

  /**
   * 必要な内容を参照できるよう、プロジェクトを読み取る。
   *
   * <p>Read a project's authoritative log without changing the selected project or run scope.
   */
  public List<ConversationLogEntry> readProject(String projectId) {
    var entries = new ArrayList<ConversationLogEntry>();
    visitProject(projectId, entries::add);
    entries.sort(Comparator.comparing(ConversationLogEntry::timestamp));
    return entries;
  }

  /**
   * 会話履歴と受付記録の管理に関する対象の確認プロジェクトを処理する。
   *
   * <p>Streams one project without retaining message bodies or changing Shell/Agent scope.
   */
  public void visitProject(
      String projectId, java.util.function.Consumer<ConversationLogEntry> visitor) {
    Path location =
        dev.mikoto2000.rei.core.project.ProjectStorage.directory(projectId)
            .resolve("conversations");
    if (!Files.isDirectory(location)) return;
    try (var files = Files.list(location)) {
      for (Path file :
          files.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList()) {
        readFile(
            file,
            entry -> {
              if (entry.conversationId() != null
                  && entry.conversationId().startsWith("project:" + projectId + ":")
                  && entry.timestamp() != null) visitor.accept(entry);
            });
      }
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read conversation history", error);
    }
  }

  /**
   * 会話履歴と受付記録の管理で受け渡す会話要約を表す。
   *
   * @param conversationId 会話の識別子
   * @param updatedAt 更新日時
   * @param messageCount メッセージ件数
   */
  public record ConversationSummary(
      String conversationId, OffsetDateTime updatedAt, long messageCount) {}

  /** 利用可能な対象を確認できるよう、会話一覧を一覧にする。 */
  public List<ConversationSummary> listConversations(String projectId, int limit, int offset) {
    if (limit < 1 || offset < 0) throw new IllegalArgumentException("Invalid history page");
    var summaries = new java.util.HashMap<String, ConversationSummary>();
    visitProject(
        projectId,
        entry ->
            summaries.compute(
                entry.conversationId(),
                (id, previous) ->
                    new ConversationSummary(
                        id,
                        previous == null || entry.timestamp().isAfter(previous.updatedAt())
                            ? entry.timestamp()
                            : previous.updatedAt(),
                        previous == null ? 1 : previous.messageCount() + 1)));
    return summaries.values().stream()
        .sorted(
            Comparator.comparing(ConversationSummary::updatedAt)
                .reversed()
                .thenComparing(ConversationSummary::conversationId))
        .skip(offset)
        .limit(limit)
        .toList();
  }

  /** 会話履歴と受付記録の管理で用いる直近会話を返す。 */
  public List<ConversationLogEntry> recentConversation(
      String projectId, String conversationId, int limit) {
    if (limit < 1) throw new IllegalArgumentException("limit must be positive");
    /**
     * 会話履歴と受付記録の管理で受け渡すOrderedを表す。
     *
     * @param entry 項目
     * @param sequence 発生順序
     */
    record Ordered(ConversationLogEntry entry, long sequence) {}
    var order =
        Comparator.comparing((Ordered e) -> e.entry().timestamp())
            .thenComparingLong(Ordered::sequence);
    var recent = new java.util.PriorityQueue<Ordered>(order);
    long[] sequence = {0};
    visitProject(
        projectId,
        entry -> {
          if (entry.conversationId().equals(conversationId)) {
            recent.add(new Ordered(entry, sequence[0]++));
            if (recent.size() > limit) recent.remove();
          }
        });
    return recent.stream().sorted(order).map(Ordered::entry).toList();
  }

  /** 必要な内容を参照できるよう、ディレクトリを読み取る。 */
  private List<ConversationLogEntry> readDirectory(Path directory) {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<ConversationLogEntry> entries = new ArrayList<>();
    try (var files = Files.list(directory)) {
      for (Path file :
          files
              .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
              .sorted()
              .toList()) {
        readFile(file, entries);
      }
    } catch (IOException e) {
      log.warn("Failed to read conversation log directory: {}", directory, e);
    }
    entries.sort(Comparator.comparing(ConversationLogEntry::timestamp));
    return entries;
  }

  /** 必要な内容を参照できるよう、ファイルを読み取る。 */
  private void readFile(Path file, List<ConversationLogEntry> entries) {
    readFile(file, entries::add);
  }

  /** 必要な内容を参照できるよう、ファイルを読み取る。 */
  private void readFile(Path file, java.util.function.Consumer<ConversationLogEntry> visitor) {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        try {
          visitor.accept(objectMapper.readValue(line, ConversationLogEntry.class));
        } catch (IOException e) {
          log.warn("Skipping malformed conversation log line in {}", file);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read conversation log file: {}", file, e);
    }
  }

  /** 会話履歴と受付記録の管理で用いる対象範囲所属を返す。 */
  static String scopeOf(String conversationId) {
    if (conversationId.startsWith("project:"))
      conversationId = conversationId.substring(conversationId.indexOf(':', 8) + 1);
    if (conversationId.startsWith("bluesky-reply:")) return "bluesky-reply";
    if (conversationId.startsWith("bluesky-manual:")) return "bluesky-manual";
    if (conversationId.startsWith("tool:")) return "tool";
    return "chat";
  }

  /** 同じ意味の指定を共通に扱えるよう、読み上げ先の表記をそろえる。 */
  private String normalizeSpeaker(String speaker) {
    return speaker == null ? "" : speaker.toLowerCase(Locale.ROOT);
  }

  /** 会話履歴と受付記録の管理で用いるディレクトリ対象を返す。 */
  private Path directoryFor(String conversationId) {
    if (directory != null) return directory;
    String id = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversationId);
    return (id == null
            ? dev.mikoto2000.rei.core.project.ProjectStorage.currentDirectory()
            : dev.mikoto2000.rei.core.project.ProjectStorage.directory(id))
        .resolve("conversations");
  }
}
