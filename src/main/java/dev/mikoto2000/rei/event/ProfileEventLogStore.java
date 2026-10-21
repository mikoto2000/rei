package dev.mikoto2000.rei.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 利用者情報イベントログの記録を保持し、後続の処理で参照できるようにする。 */
@Component
public class ProfileEventLogStore implements AgentEventListener {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(ProfileEventLogStore.class);

  /** ファイル。 */
  private final Path file;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper objectMapper;

  /** 書き込み排他制御。 */
  private final Object writeLock = new Object();

  /** 管理対象。 */
  private dev.mikoto2000.rei.storage.ManagedActivityLog managed;

  /** 実行状況の通知で使用する管理対象作業状況ログを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setManagedActivityLog(dev.mikoto2000.rei.storage.ManagedActivityLog managed) {
    this.managed = managed;
  }

  /** 実行状況の通知を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProfileEventLogStore() {
    this(null, new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  /** 指定された値や利用先を使って、ProfileEventLogStoreを初期化する。 */
  public ProfileEventLogStore(Path file, ObjectMapper objectMapper) {
    this.file = file;
    this.objectMapper = objectMapper;
  }

  /** 実行状況の通知で用いるファイルを返す。 */
  public Path file() {
    if (managed != null && file == null) {
      var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
      return managed.legacyFile(project == null ? null : project.id());
    }
    return file != null
        ? file
        : dev.mikoto2000.rei.core.project.ProjectStorage.currentDirectory()
            .resolve("logs/activity.jsonl");
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  @Override
  public void onEvent(AgentEvent event) {
    append(event);
  }

  /** 既存の記録へ経緯を残すため、利用者情報イベントログ保存先を追記する。 */
  public void append(AgentEvent event) {
    if (event == null) {
      return;
    }
    ProfileEventLogEntry entry = toEntry(event);
    if (managed != null && this.file == null) {
      managed.append(event.projectId(), entry);
      return;
    }
    Path file =
        this.file != null
            ? this.file
            : (event.projectId() != null
                    ? dev.mikoto2000.rei.core.project.ProjectStorage.directory(event.projectId())
                    : dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current())
                .resolve("logs/activity.jsonl");
    synchronized (writeLock) {
      try {
        Files.createDirectories(file.getParent());
        Files.writeString(
            file,
            objectMapper.writeValueAsString(entry) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        log.warn("Failed to append profile event log: {}", file, e);
      }
    }
  }

  /** 必要な内容を参照できるよう、全対象を読み取る。 */
  public List<ProfileEventLogEntry> readAll() {
    if (managed != null && this.file == null) {
      var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
      return managed.read(project == null ? null : project.id());
    }
    Path file = file();
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    List<ProfileEventLogEntry> entries = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        try {
          entries.add(objectMapper.readValue(line, ProfileEventLogEntry.class));
        } catch (IOException e) {
          log.warn("Skipping malformed profile event log line in {}", file);
        }
      }
    } catch (IOException e) {
      log.warn("Failed to read profile event log: {}", file, e);
    }
    entries.sort(Comparator.comparing(ProfileEventLogEntry::timestamp));
    return entries;
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
  public ProfileSummary summarize() {
    List<ProfileEventLogEntry> entries = readAll();
    Map<String, Long> countsByType = new LinkedHashMap<>();
    Map<String, DurationStats> durationsByType = new LinkedHashMap<>();
    for (ProfileEventLogEntry entry : entries) {
      countsByType.merge(entry.type(), 1L, Long::sum);
      Long duration = durationOf(entry);
      if (duration != null) {
        durationsByType.computeIfAbsent(entry.type(), ignored -> new DurationStats()).add(duration);
      }
    }
    Instant first = entries.isEmpty() ? null : entries.getFirst().timestamp();
    Instant last = entries.isEmpty() ? null : entries.getLast().timestamp();
    return new ProfileSummary(file(), entries.size(), first, last, countsByType, durationsByType);
  }

  /** 実行状況の通知で用いる集計区分一覧を返す。 */
  public List<ProfileBucket> buckets(Duration bucketSize) {
    if (bucketSize == null || bucketSize.isZero() || bucketSize.isNegative()) {
      throw new IllegalArgumentException("bucketSize must be positive");
    }
    List<ProfileEventLogEntry> entries = readAll();
    if (entries.isEmpty()) {
      return List.of();
    }
    Instant start = entries.getFirst().timestamp();
    Map<Long, Long> counts = new LinkedHashMap<>();
    long bucketMillis = bucketSize.toMillis();
    for (ProfileEventLogEntry entry : entries) {
      long index =
          Math.max(0L, Duration.between(start, entry.timestamp()).toMillis() / bucketMillis);
      counts.merge(index, 1L, Long::sum);
    }
    List<ProfileBucket> buckets = new ArrayList<>();
    for (Map.Entry<Long, Long> count : counts.entrySet()) {
      Instant bucketStart = start.plusMillis(count.getKey() * bucketMillis);
      buckets.add(new ProfileBucket(bucketStart, count.getValue()));
    }
    return buckets;
  }

  /** 利用先へ渡せるよう、項目の形式に変換する。 */
  private ProfileEventLogEntry toEntry(AgentEvent event) {
    AgentEventPayload payload = event.payload();
    return new ProfileEventLogEntry(
        event.id(),
        event.sequence(),
        event.timestamp(),
        event.type().value(),
        event.version(),
        event.sessionId(),
        event.turnId(),
        event.runId(),
        event.correlationId(),
        event.parentEventId(),
        payload == null ? null : payload.getClass().getSimpleName(),
        summarizePayload(payload));
  }

  /** 利用者が要点を把握できるよう、通知内容を要約する。 */
  private Map<String, Object> summarizePayload(AgentEventPayload payload) {
    if (payload == null) {
      return Map.of();
    }
    Map<String, Object> values =
        objectMapper.convertValue(payload, new TypeReference<Map<String, Object>>() {});
    replaceTextWithLength(values, "delta");
    replaceTextWithLength(values, "text");
    replaceTextWithLength(values, "argumentsSummary");
    replaceTextWithLength(values, "resultSummary");
    return values;
  }

  /** 指定された内容へ切り替えるため、テキスト付き長さを置き換える。 */
  private void replaceTextWithLength(Map<String, Object> values, String key) {
    Object value = values.remove(key);
    if (value instanceof String text) {
      values.put(key + "Length", text.length());
    } else if (value != null) {
      values.put(key, value);
    }
  }

  /** 実行状況の通知で用いる継続時間所属を返す。 */
  private Long durationOf(ProfileEventLogEntry entry) {
    if (entry.payload() == null) {
      return null;
    }
    Object duration = entry.payload().get("duration");
    if (duration == null) {
      duration = entry.payload().get("durationMs");
    }
    if (duration instanceof Number number) {
      return number.longValue();
    }
    return null;
  }

  /**
   * 実行状況の通知で受け渡す利用者情報要約を表す。
   *
   * @param file ファイル
   * @param total 合計
   * @param first 最初
   * @param last 直近
   * @param countsByType 件数別種別
   * @param durationsByType 継続時間一覧別種別
   */
  public record ProfileSummary(
      Path file,
      int total,
      Instant first,
      Instant last,
      Map<String, Long> countsByType,
      Map<String, DurationStats> durationsByType) {}

  /**
   * 実行状況の通知で受け渡す利用者情報集計区分を表す。
   *
   * @param start 開始
   * @param count 件数
   */
  public record ProfileBucket(Instant start, long count) {}

  /** 実行状況の通知に必要な継続時間統計を扱う。 */
  public static final class DurationStats {
    /** 件数。 */
    private long count;

    /** 合計ミリ秒数。 */
    private long totalMillis;

    /** 最小ミリ秒数。 */
    private long minMillis = Long.MAX_VALUE;

    /** 最大ミリ秒数。 */
    private long maxMillis = Long.MIN_VALUE;

    /** 新たな対象を登録し、後から利用できるようにする。 */
    void add(long millis) {
      count++;
      totalMillis += millis;
      minMillis = Math.min(minMillis, millis);
      maxMillis = Math.max(maxMillis, millis);
    }

    /** 対象の規模を判断するため、継続時間統計を数える。 */
    public long count() {
      return count;
    }

    /** 実行状況の通知で用いる合計ミリ秒数を返す。 */
    public long totalMillis() {
      return totalMillis;
    }

    /** 実行状況の通知で用いる最小ミリ秒数を返す。 */
    public long minMillis() {
      return count == 0 ? 0L : minMillis;
    }

    /** 実行状況の通知で用いる最大ミリ秒数を返す。 */
    public long maxMillis() {
      return count == 0 ? 0L : maxMillis;
    }

    /** 実行状況の通知で用いる平均ミリ秒数を返す。 */
    public long averageMillis() {
      return count == 0 ? 0L : Math.round((double) totalMillis / count);
    }
  }
}
