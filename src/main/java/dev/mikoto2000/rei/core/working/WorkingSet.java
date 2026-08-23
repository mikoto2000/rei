package dev.mikoto2000.rei.core.working;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;

/**
 * 現在のタスクで実際に使用されたファイルの集合を保持する。
 *
 * <p>検索結果だけでは追加せず、read / write / edit / create の成功時のみ登録する。
 * 最大件数を超えた場合は lastAccessedAt が古いものから削除する（LRU 相当）。</p>
 */
public class WorkingSet {

  private static final Logger log = LoggerFactory.getLogger(WorkingSet.class);
  private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
      "(?i)((?:api[_-]?key|access[_-]?token|token|password|secret)\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");

  static final int DEFAULT_MAX_FILES = 20;

  private final int maxFiles;
  private final Clock clock;
  private final AgentEventFactory events;
  private final AgentEventBus eventBus;
  private final Map<String, FileReference> files = new LinkedHashMap<>();
  private final ThreadLocal<String> activeSearchId = new ThreadLocal<>();

  public WorkingSet() {
    this(DEFAULT_MAX_FILES, Clock.systemDefaultZone());
  }

  public WorkingSet(int maxFiles, Clock clock) {
    this(maxFiles, clock, null, null);
  }

  public WorkingSet(int maxFiles, Clock clock, AgentEventFactory events, AgentEventBus eventBus) {
    this.maxFiles = Math.max(1, maxFiles);
    this.clock = clock;
    this.events = events;
    this.eventBus = eventBus;
  }

  /**
   * ファイルを読み込んだことを記録する。
   */
  public void recordRead(Path path) {
    addOrTouch(normalize(path), FileReference.read(normalize(path), now()));
  }

  /**
   * ファイルを書き込んだ（作成・上書き）ことを記録する。
   */
  public void recordWrite(Path path) {
    String normalized = normalize(path);
    addOrTouch(normalized, FileReference.write(normalized, now(), now()));
  }

  /**
   * ファイルを編集したことを記録する。
   */
  public void recordEdit(Path path) {
    String normalized = normalize(path);
    addOrTouch(normalized, FileReference.edit(normalized, now(), now()));
  }

  /**
   * 新規ファイルを作成したことを記録する。
   */
  public void recordCreate(Path path) {
    String normalized = normalize(path);
    addOrTouch(normalized, FileReference.created(normalized, now(), now()));
  }

  /**
   * 指定パスを Working Set から削除する。
   */
  public void remove(Path path) {
    remove(normalize(path), "explicit removal");
  }

  /**
   * Working Set を空にする。
   */
  public void clear() {
    List.copyOf(files.keySet()).forEach(path -> remove(path, "clear"));
  }

  /**
   * 現在の Working Set をアクセス順（新しい順）で返す。
   */
  public List<FileReference> getFiles() {
    return files.values().stream()
        .sorted(Comparator.comparing(FileReference::lastAccessedAt).reversed())
        .toList();
  }

  /**
   * 指定パスが Working Set に存在するかどうか。
   */
  public boolean contains(Path path) {
    return files.containsKey(normalize(path));
  }

  /**
   * 指定パスが Working Set に存在する場合、その参照を返す。
   */
  public Optional<FileReference> find(Path path) {
    return Optional.ofNullable(files.get(normalize(path)));
  }

  /**
   * Working Set が空かどうか。
   */
  public boolean isEmpty() {
    return files.isEmpty();
  }

  /**
   * Working Set の最大件数。
   */
  public int maxFiles() {
    return maxFiles;
  }

  private void addOrTouch(String normalized, FileReference reference) {
    FileReference existing = files.get(normalized);
    if (existing != null) {
      FileReference updated = existing.withAccessedAt(reference.lastAccessedAt());
      if (reference.lastModifiedAt() != null) {
        updated = updated.withModifiedAt(reference.lastModifiedAt());
      }
      files.put(normalized, updated);
      log.debug("Working set: touched {} ({})", normalized, updated.accessType());
      return;
    }
    files.put(normalized, reference);
    log.debug("Working set: added {} ({})", normalized, reference.accessType());
    publishAdded(reference);
    evictIfNeeded();
  }

  private void evictIfNeeded() {
    while (files.size() > maxFiles) {
      String oldest = files.values().stream()
          .min(Comparator.comparing(FileReference::lastAccessedAt))
          .map(FileReference::path)
          .orElse(null);
      if (oldest == null) {
        return;
      }
      remove(oldest, "capacity eviction");
      log.debug("Working set: evicted {}", oldest);
    }
  }

  private String normalize(Path path) {
    return path.toAbsolutePath().normalize().toString();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  /**
   * LLM コンテキストに渡す簡潔な表現を組み立てる。
   */
  public String renderForPrompt() {
    List<FileReference> refs = getFiles();
    if (refs.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("## Current Working Set\n\n");
    sb.append("The following files were recently used in the current task:\n\n");
    for (FileReference ref : refs) {
      sb.append("- ").append(ref.path()).append(" [").append(ref.accessType()).append("]\n");
    }
    sb.append("\n");
    sb.append("Before searching for files, check whether the required file is already present in this working set.\n");
    sb.append("Do not search again merely to rediscover a file whose path is already known.\n");
    sb.append("If the contents are required, read the known file directly.\n");
    sb.append("Search for files only when:\n");
    sb.append("- the required file is not known;\n");
    sb.append("- additional related files must be discovered;\n");
    sb.append("- the known path is no longer valid;\n");
    sb.append("- the current working set is insufficient for the task.\n");
    return sb.toString();
  }

  /**
   * ファイルが存在しなくなった場合に Working Set から除去する。
   */
  public void removeIfMissing(Path path) {
    if (!java.nio.file.Files.exists(path)) {
      remove(normalize(path), "missing file");
      log.debug("Working set: removed missing file {}", normalize(path));
    }
  }

  private void remove(String normalized, String reason) {
    FileReference removed = files.remove(normalized);
    if (removed == null) {
      return;
    }
    log.debug("Working set: removed {}", normalized);
    if (events != null && eventBus != null) {
      eventBus.publish(events.workingSetItemRemoved(normalized, reason));
    }
  }

  private void publishAdded(FileReference reference) {
    if (events != null && eventBus != null) {
      eventBus.publish(events.workingSetItemAdded(reference.path(), "file", reference.path(), reference.path(),
          reference.accessType(), activeSearchId.get()));
    }
  }

  /** Begins the observable lifecycle around the existing search-and-select path. */
  public SearchObservation beginSearch(String query, String strategy) {
    String searchId = "ws-search-" + UUID.randomUUID();
    SearchObservation observation = new SearchObservation(searchId, System.nanoTime(), files.size());
    activeSearchId.set(searchId);
    if (events != null && eventBus != null) {
      eventBus.publish(events.workingSetSearchStarted(searchId, bounded(query, 500), strategy, files.size()));
    }
    return observation;
  }

  /** Completes a search using aggregate values already produced by the search algorithm. */
  public void completeSearch(SearchObservation observation, int hitCount, int candidateCount, int selectedCount,
      int alreadyPresentCount) {
    long durationMs = Math.max(0L, (System.nanoTime() - observation.startedAtNanos()) / 1_000_000L);
    if (events != null && eventBus != null) {
      eventBus.publish(events.workingSetSearchCompleted(observation.searchId(), durationMs, hitCount, candidateCount,
          selectedCount, alreadyPresentCount, observation.workingSetSizeBefore(), files.size()));
    }
    activeSearchId.remove();
  }

  /** Clears correlation state if the underlying search aborts before producing metrics. */
  public void abandonSearch(SearchObservation observation) {
    activeSearchId.remove();
  }

  private String bounded(String value, int maxLength) {
    if (value == null) return "";
    String redacted = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1[REDACTED]");
    String safe = redacted.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
    return safe.length() <= maxLength ? safe : safe.substring(0, maxLength - 1) + "…";
  }

  public record SearchObservation(String searchId, long startedAtNanos, int workingSetSizeBefore) {
  }
}
